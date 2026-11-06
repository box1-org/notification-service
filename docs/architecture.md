# Architecture

The notification service owns notice intents, delivery attempts, receipts and failure status. It does not own order
state and never reads another service's tables. Design records are in the run state for
[box1-docs#16](https://github.com/box1-org/box1-docs/tree/agent-run/16-notification-service/agent-runs/16-notification-service/decisions).

```text
box1.order.events.v1 ─┐                                   ┌─> stub provider ─> delivery_receipt
                      ├─> consumer ─> notification (+source, processed_event)  ─> delivery worker
box1.fulfillment.events.v1 ┘     │                          └─> delivery_attempt, status, outbox ─> box1.notification.events.v1
                                 └─ poison ─> box1.notification.dlq.v1 (unchanged)
```

## Notice catalog (D-002)

| Trigger | Notice type | Channel |
|---|---|---|
| `order.state-changed.v1` to `SHIPPED`, or `shipment.confirmed.v1` | `ORDER_SHIPPED` | `ops-fulfillment` |
| `shipment.failed.v1`, `retryable=true` (no order state change) | `SHIPMENT_DELAYED` | `ops-fulfillment` |
| `shipment.failed.v1`, `retryable=false`, or `EXCEPTION` with reason `SHIPMENT_FAILED` | `SHIPMENT_FAILED` | `ops-fulfillment` |
| `order.state-changed.v1` to `EXCEPTION`, any other reason | `ORDER_EXCEPTION` | `ops-orders` |
| `order.state-changed.v1` to `REJECTED` | `ORDER_REJECTED` | `ops-orders` |

`COMPLETED` and the routine states give no notice. `order.placed.v1` and `fulfillment.requested.v1` are checked at the
envelope only. The `order.placed.v1` payload, the only one with `customerId`, is never read.

Templates are plain text and versioned as `{noticeType}@v{n}`. They substitute only allowlisted identifiers, states and
reason codes, and they escape control characters and markup or chat syntax.

## One fact, one notice (D-003)

- **Event layer.** `processed_event` records each `eventId` in the same transaction as its effect, so a redelivered
  event is a no-op.
- **Fact layer.** `notification.dedupe_key` is unique and built from the business fact (`orderId`, notice type and, for
  delays, the request and reason). It is never built from the event. A second event that reports the same fact adds a
  `notification_source` row and creates no notice.
- **Delivery layer.** The provider gets `notificationId` as an idempotency key, and `delivery_receipt.notification_id` is
  unique. A retry after a crash returns the existing receipt.

## Delivery (D-004)

`PENDING` → `RETRY_SCHEDULED` → `DELIVERED` | `FAILED`.

- A scheduled worker claims due notices with `FOR UPDATE SKIP LOCKED` and a 30 s lease.
- It calls the provider outside any transaction, with a 2 s timeout.
- It records the attempt, the status and (when terminal) the outbox event in one transaction, guarded by the lease.
- It makes at most 6 attempts, backing off 2, 4, 8, 16 and 32 s. Database time decides when a retry is due.
- Outcomes:
  - 503, 5xx, 408, 429, timeouts and errors are transient.
  - Any other 4xx is permanent and is not retried.
  - Exhaustion ends in `FAILED` / `RETRIES_EXHAUSTED`.
- Failure reasons are structured as `{category, code, httpStatus}`. Provider text is never stored.

## Outbox and events (D-005)

`notification.delivered.v1` or `notification.failed.v1` is written to `outbox` in the same transaction as the terminal
status. The publisher is the order-service pattern:

- it claims the oldest unpublished row per order;
- it sends with `acks=all` and idempotence, then marks the row published after the broker acknowledges;
- it retries forever with capped backoff.

Key and `aggregateId` are `orderId`. `causationId` is the event that created the notice. Kafka is not a readiness
dependency: a broker outage only grows the outbox.

## Consumers (D-006)

- Group `notification` on both topics, `auto.offset.reset=earliest`, plus the required `NOTIFY_EVENTS_FLOOR`.
- Records that cannot be processed go unchanged to `box1.notification.dlq.v1` through the connector's dead-letter strategy:
  - invalid UTF-8 or JSON;
  - an envelope that breaks the v1 rules;
  - an event type the topic does not carry;
  - a consumed payload that breaks its schema rules.
- A database outage holds the channel without using up attempts. Other errors get bounded retries, then go to the DLQ.

## Access (D-007)

smallrye-jwt verifies issuer, signature, expiry and audience, then `ClientRoleAugmentor` grants roles by `azp`:

| `azp` | Roles |
|---|---|
| `notification-service` | `resource_access.notification-service.roles`: `notification-reader`, `notification-fault-operator` |
| the console client, only if `NOTIFY_AUTH_CONSOLE_CLIENT_ID` is set | `notification-reader` if the user holds a configured console role; never fault control |
| anything else | none (403) |

Every `/api/v1` endpoint has `@RolesAllowed`, unannotated endpoints are denied, and the smoke paths are anonymous.

## Stub fault control (D-009)

- **Modes:**
  - `TRANSIENT_503`;
  - `SLOW`, a 100–5000 ms delay, which becomes a timeout above 2 s;
  - `PERMANENT_4XX`, which returns 422.
- **Bounds:**
  - The TTL is 10–900 s, against database time.
  - An optional `failCount` of 1–20 ends the fault after that many calls.
  - Only one fault is active at a time.
- **Gate:** fault control is open only when `NOTIFY_STUB_FAULTS_ENABLED=true` and the downward-API `POD_NAMESPACE` is
  in `NOTIFY_STUB_FAULT_NAMESPACES`.
- **Audit:** every request, accepted or refused, is written with its actor (`preferred_username`, else `sub`) to the
  append-only `stub_fault_audit`, alongside expiries and exhaustion. Triggers block UPDATE and TRUNCATE, and block
  DELETE for 90 days.

## Data and retention (D-010, D-012)

All tables are new and owned by this service (Flyway `V1__notification_schema.sql`). Terminal notices with their
attempts, sources and receipts are kept for 30 days, processed events for 30 days and published outbox rows for 7 days.
No table, event, receipt, log line or metric label holds a customer identifier. The tests scan for a sentinel
`customerId` sent in `order.placed.v1`.

## Observability (D-011)

- **Logs:** JSON in production, with MDC fields `orderId`, `notificationId`, `eventId`, `eventType` and `correlationId`.
- **Traces:** consume and deliver spans are parented on the envelope's `traceparent`. The API returns `correlationId`
  and `traceparent` for each notice. Export is off until a collector exists (#22).
- **Metrics:**
  - `box1_notification_pending`, `box1_notification_oldest_pending_age_seconds`
  - `box1_notification_delivery_attempts_total{outcome}`
  - `box1_notification_delivered_total`, `box1_notification_failed_total{category}`
  - `box1_notification_events_consumed_total{type,outcome}`
  - `box1_outbox_pending`, `box1_outbox_oldest_pending_age_seconds`, `box1_outbox_publish_failures_total`

## Limits

- One replica. The design is safe with more (`SKIP LOCKED`, leases), but that is not tested.
- No real provider exists. One would need a secret and approval.
- The Kafka listener is plaintext with no client authentication. That is a platform limit, not addressed here.
- A poisoned `order.placed.v1` record is copied unchanged to the DLQ, keeping order-topic data for 14 days instead of 7.
