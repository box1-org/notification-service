-- Notification service schema v1 (box1-docs#16). New tables only; this service owns all of them.
-- No column holds customer data: notices are built from order, fulfillment and shipment IDs, states and reason codes.

-- Consumed events, recorded in the same transaction as their effect. A redelivered event is a no-op.
CREATE TABLE processed_event (
    event_id     uuid PRIMARY KEY,
    event_type   text NOT NULL,
    outcome      text NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_processed_event_at ON processed_event (processed_at);

-- One row per business fact (dedupe_key), whichever event reported it first.
CREATE TABLE notification (
    notification_id     text PRIMARY KEY CHECK (notification_id ~ '^ntf_[0-9a-f]{26}$'),
    dedupe_key          text NOT NULL UNIQUE CHECK (length(dedupe_key) <= 512),
    order_id            text NOT NULL CHECK (order_id ~ '^ord_[A-Za-z0-9_-]{1,124}$'),
    notice_type         text NOT NULL CHECK (notice_type IN
                            ('ORDER_SHIPPED', 'SHIPMENT_DELAYED', 'SHIPMENT_FAILED', 'ORDER_EXCEPTION', 'ORDER_REJECTED')),
    channel             text NOT NULL CHECK (channel ~ '^ops-[a-z0-9-]{1,40}$'),
    template_version    text NOT NULL,
    body                text NOT NULL CHECK (length(body) <= 2000),
    status              text NOT NULL CHECK (status IN ('PENDING', 'RETRY_SCHEDULED', 'DELIVERED', 'FAILED')),
    attempt_count       integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at     timestamptz NULL,
    claimed_by          text NULL,
    claimed_until       timestamptz NULL,
    failure_category    text NULL CHECK (failure_category IN ('RETRIES_EXHAUSTED', 'PERMANENT_REJECTION')),
    failure_code        text NULL CHECK (failure_code IN
                            ('PROVIDER_UNAVAILABLE', 'PROVIDER_TIMEOUT', 'PROVIDER_REJECTED', 'PROVIDER_ERROR')),
    failure_http_status integer NULL,
    correlation_id      text NOT NULL,
    causation_id        uuid NOT NULL,
    traceparent         text NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    terminal_at         timestamptz NULL,
    CHECK ((status IN ('DELIVERED', 'FAILED')) = (terminal_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_category IS NOT NULL))
);
CREATE INDEX ix_notification_order ON notification (order_id, created_at DESC);
CREATE INDEX ix_notification_due ON notification (next_attempt_at) WHERE status IN ('PENDING', 'RETRY_SCHEDULED');
CREATE INDEX ix_notification_terminal ON notification (terminal_at) WHERE terminal_at IS NOT NULL;

-- Every event that reported a notification's fact.
CREATE TABLE notification_source (
    notification_id text NOT NULL REFERENCES notification (notification_id) ON DELETE CASCADE,
    event_id        uuid NOT NULL,
    event_type      text NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (notification_id, event_id)
);

CREATE TABLE delivery_attempt (
    notification_id text NOT NULL REFERENCES notification (notification_id) ON DELETE CASCADE,
    attempt         integer NOT NULL CHECK (attempt >= 1),
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz NOT NULL DEFAULT now(),
    outcome         text NOT NULL CHECK (outcome IN ('SUCCESS', 'TRANSIENT', 'PERMANENT')),
    failure_code    text NULL,
    http_status     integer NULL,
    PRIMARY KEY (notification_id, attempt)
);

-- One effective delivery per notification. The stub writes this; the body itself is not kept here.
CREATE TABLE delivery_receipt (
    receipt_id      text PRIMARY KEY CHECK (receipt_id ~ '^rcpt_[0-9a-f]{24}$'),
    notification_id text NOT NULL UNIQUE REFERENCES notification (notification_id) ON DELETE CASCADE,
    provider        text NOT NULL,
    channel         text NOT NULL,
    body_sha256     text NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    body_length     integer NOT NULL,
    accepted_at     timestamptz NOT NULL DEFAULT now()
);

-- Transactional outbox, as in order-service and inventory-service. One terminal event per notification.
CREATE TABLE outbox (
    seq              bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_id         uuid PRIMARY KEY,
    aggregate_id     text NOT NULL,
    notification_id  text NOT NULL UNIQUE,
    event_type       text NOT NULL CHECK (event_type IN ('notification.delivered.v1', 'notification.failed.v1')),
    payload          jsonb NOT NULL,
    created_at       timestamptz NOT NULL,
    published_at     timestamptz NULL,
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL,
    claimed_by       text NULL,
    claim_expires_at timestamptz NULL,
    last_error       text NULL
);
CREATE INDEX ix_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_aggregate_unpublished ON outbox (aggregate_id, seq) WHERE published_at IS NULL;

-- The stub provider's single active fault, if any. Expiry is compared with database time.
CREATE TABLE stub_fault (
    id             smallint PRIMARY KEY CHECK (id = 1),
    mode           text NOT NULL CHECK (mode IN ('TRANSIENT_503', 'SLOW', 'PERMANENT_4XX')),
    delay_millis   integer NOT NULL DEFAULT 0 CHECK (delay_millis BETWEEN 0 AND 5000),
    fail_remaining integer NULL CHECK (fail_remaining IS NULL OR fail_remaining >= 0),
    expires_at     timestamptz NOT NULL,
    set_by         text NOT NULL,
    set_at         timestamptz NOT NULL DEFAULT now()
);

-- Append-only audit of every fault-control request, including refusals and expiries.
CREATE TABLE stub_fault_audit (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL CHECK (action IN ('SET', 'CLEAR', 'EXPIRED', 'EXHAUSTED')),
    mode        text NULL,
    ttl_seconds integer NULL,
    outcome     text NOT NULL,
    namespace   text NULL
);

CREATE FUNCTION stub_fault_audit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'stub_fault_audit is append-only';
    END IF;
    IF TG_OP = 'DELETE' AND OLD.at > now() - interval '90 days' THEN
        RAISE EXCEPTION 'stub_fault_audit rows are kept for 90 days';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'stub_fault_audit cannot be truncated';
END;
$$;
CREATE TRIGGER stub_fault_audit_no_update BEFORE UPDATE OR DELETE ON stub_fault_audit
    FOR EACH ROW EXECUTE FUNCTION stub_fault_audit_guard();
CREATE TRIGGER stub_fault_audit_no_truncate BEFORE TRUNCATE ON stub_fault_audit
    FOR EACH STATEMENT EXECUTE FUNCTION stub_fault_audit_guard();
