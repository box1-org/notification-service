package org.box1.notification.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** SQL for notification intents, their sources, attempts and receipts. Times come from the database clock. */
@ApplicationScoped
public class NotificationStore {
    private static final String CLAIM = """
            WITH due AS (
                SELECT notification_id FROM notification
                WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
                  AND next_attempt_at <= now()
                  AND (claimed_until IS NULL OR claimed_until < now())
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notification n
            SET claimed_by = ?, claimed_until = now() + make_interval(secs => ?)
            FROM due
            WHERE n.notification_id = due.notification_id
            RETURNING n.notification_id, n.order_id, n.notice_type, n.channel, n.template_version, n.body,
                      n.attempt_count, n.correlation_id, n.causation_id, n.traceparent
            """;
    private static final String COLUMNS = "notification_id, order_id, notice_type, channel, template_version, body, status,"
            + " attempt_count, next_attempt_at, failure_category, failure_code, failure_http_status, correlation_id,"
            + " causation_id, traceparent, created_at, updated_at, terminal_at";

    private final DataSource dataSource;

    public NotificationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Record a consumed event in the caller's transaction. False means it was already processed. */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean markProcessed(UUID eventId, String eventType, String outcome) {
        String sql = "INSERT INTO processed_event (event_id, event_type, outcome) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, eventId);
            s.setString(2, eventType);
            s.setString(3, outcome);
            return s.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Insert an intent unless its fact already has one. True when this call created it. */
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean insertIntent(NewIntent intent) {
        String sql = "INSERT INTO notification (notification_id, dedupe_key, order_id, notice_type, channel, template_version,"
                + " body, status, next_attempt_at, correlation_id, causation_id, traceparent)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', now(), ?, ?, ?) ON CONFLICT (dedupe_key) DO NOTHING";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, intent.notificationId());
            s.setString(2, intent.dedupeKey());
            s.setString(3, intent.orderId());
            s.setString(4, intent.noticeType());
            s.setString(5, intent.channel());
            s.setString(6, intent.templateVersion());
            s.setString(7, intent.body());
            s.setString(8, intent.correlationId());
            s.setObject(9, intent.causationId());
            s.setString(10, intent.traceparent());
            return s.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void addSource(String notificationId, UUID eventId, String eventType) {
        String sql = "INSERT INTO notification_source (notification_id, event_id, event_type) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            s.setObject(2, eventId);
            s.setString(3, eventType);
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Claim due intents under a lease. A crashed worker's lease expires and the intent becomes claimable again. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Claimed> claimDue(String owner, int limit, double leaseSeconds) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(CLAIM)) {
            s.setInt(1, limit);
            s.setString(2, owner);
            s.setDouble(3, leaseSeconds);
            List<Claimed> claimed = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    claimed.add(new Claimed(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), r.getString(6), r.getInt(7), r.getString(8), r.getObject(9, UUID.class),
                            r.getString(10)));
                }
            }
            return claimed;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /**
     * Record one attempt and the resulting status in the caller's transaction, only while this owner still holds the
     * lease. Returns the database time of the change, or empty if the lease was lost (another worker owns the intent).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Instant> recordAttempt(Claimed intent, String owner, AttemptRecord attempt, String newStatus,
            double retryDelaySeconds, String failureCategory) {
        String update = "UPDATE notification SET status = ?, attempt_count = ?, updated_at = now(),"
                + " next_attempt_at = CASE WHEN ? = 'RETRY_SCHEDULED' THEN now() + make_interval(secs => ?) ELSE NULL END,"
                + " terminal_at = CASE WHEN ? IN ('DELIVERED', 'FAILED') THEN now() ELSE NULL END,"
                + " failure_category = ?, failure_code = ?, failure_http_status = ?, claimed_by = NULL, claimed_until = NULL"
                + " WHERE notification_id = ? AND claimed_by = ? AND status IN ('PENDING', 'RETRY_SCHEDULED')"
                + " RETURNING now()";
        String insert = "INSERT INTO delivery_attempt (notification_id, attempt, started_at, outcome, failure_code,"
                + " http_status) VALUES (?, ?, ?, ?, ?, ?)";
        boolean failed = "FAILED".equals(newStatus);
        try (Connection c = dataSource.getConnection(); PreparedStatement u = c.prepareStatement(update)) {
            u.setString(1, newStatus);
            u.setInt(2, attempt.attempt());
            u.setString(3, newStatus);
            u.setDouble(4, retryDelaySeconds);
            u.setString(5, newStatus);
            u.setString(6, failed ? failureCategory : null);
            u.setString(7, failed ? attempt.failureCode() : null);
            setInteger(u, 8, failed ? attempt.httpStatus() : null);
            u.setString(9, intent.notificationId());
            u.setString(10, owner);
            Instant at;
            try (ResultSet r = u.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                at = r.getTimestamp(1).toInstant();
            }
            try (PreparedStatement i = c.prepareStatement(insert)) {
                i.setString(1, intent.notificationId());
                i.setInt(2, attempt.attempt());
                i.setTimestamp(3, Timestamp.from(attempt.startedAt()));
                i.setString(4, attempt.outcome());
                i.setString(5, attempt.failureCode());
                setInteger(i, 6, attempt.httpStatus());
                i.executeUpdate();
            }
            return Optional.of(at);
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /**
     * Lock the notification row for the caller's transaction and return its receipt, if any. Called before recording a
     * non-success result, so a provider call that completed late (after a timeout) is recorded as the delivery it was.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<String> lockAndFindReceipt(String notificationId) {
        String lock = "SELECT 1 FROM notification WHERE notification_id = ? FOR UPDATE";
        String receipt = "SELECT receipt_id FROM delivery_receipt WHERE notification_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement l = c.prepareStatement(lock);
                PreparedStatement s = c.prepareStatement(receipt)) {
            l.setString(1, notificationId);
            l.executeQuery().close();
            s.setString(1, notificationId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Give a claim back without an attempt, for a worker that is shutting down. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void releaseClaim(String notificationId, String owner) {
        String sql = "UPDATE notification SET claimed_by = NULL, claimed_until = NULL WHERE notification_id = ? AND claimed_by = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            s.setString(2, owner);
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Row> findByOrder(String orderId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM notification WHERE order_id = ? ORDER BY created_at DESC, notification_id"
                + " LIMIT ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, orderId);
            s.setInt(2, limit);
            return rows(s);
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<Row> find(String notificationId) {
        String sql = "SELECT " + COLUMNS + " FROM notification WHERE notification_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            return rows(s).stream().findFirst();
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<SourceRow> sources(String notificationId) {
        String sql = "SELECT event_id, event_type, received_at FROM notification_source WHERE notification_id = ?"
                + " ORDER BY received_at, event_id";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            List<SourceRow> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new SourceRow(r.getObject(1, UUID.class), r.getString(2), instant(r, 3)));
                }
            }
            return out;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AttemptRow> attempts(String notificationId) {
        String sql = "SELECT attempt, started_at, finished_at, outcome, failure_code, http_status FROM delivery_attempt"
                + " WHERE notification_id = ? ORDER BY attempt";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            List<AttemptRow> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new AttemptRow(r.getInt(1), instant(r, 2), instant(r, 3), r.getString(4), r.getString(5),
                            integer(r, 6)));
                }
            }
            return out;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<ReceiptRow> receipt(String notificationId) {
        String sql = "SELECT receipt_id, provider, channel, body_sha256, body_length, accepted_at FROM delivery_receipt"
                + " WHERE notification_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new ReceiptRow(r.getString(1), r.getString(2), r.getString(3),
                        r.getString(4), r.getInt(5), instant(r, 6))) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Counts for metrics: non-terminal intents and the age of the oldest one, plus the outbox backlog. */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Snapshot snapshot() {
        String sql = "SELECT (SELECT count(*) FROM notification WHERE status IN ('PENDING', 'RETRY_SCHEDULED')),"
                + " (SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM notification"
                + "   WHERE status IN ('PENDING', 'RETRY_SCHEDULED')),"
                + " (SELECT count(*) FROM outbox WHERE published_at IS NULL),"
                + " (SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM outbox WHERE published_at IS NULL)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql);
                ResultSet r = s.executeQuery()) {
            r.next();
            return new Snapshot(r.getLong(1), r.getDouble(2), r.getLong(3), r.getDouble(4));
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Retention (decision D-010). Returns rows removed per table. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int[] purge(long terminalDays, long processedDays, long outboxDays) {
        String[] statements = {
            "DELETE FROM notification WHERE terminal_at < now() - make_interval(days => ?)",
            "DELETE FROM processed_event WHERE processed_at < now() - make_interval(days => ?)",
            "DELETE FROM outbox WHERE published_at < now() - make_interval(days => ?)",
        };
        long[] days = {terminalDays, processedDays, outboxDays};
        int[] removed = new int[statements.length];
        try (Connection c = dataSource.getConnection()) {
            for (int i = 0; i < statements.length; i++) {
                try (PreparedStatement s = c.prepareStatement(statements[i])) {
                    s.setInt(1, Math.toIntExact(days[i]));
                    removed[i] = s.executeUpdate();
                }
            }
            return removed;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean ping() {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("SELECT 1");
                ResultSet r = s.executeQuery()) {
            return r.next();
        } catch (SQLException error) {
            return false;
        }
    }

    private static List<Row> rows(PreparedStatement s) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                out.add(new Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7), r.getInt(8), instant(r, 9), r.getString(10), r.getString(11),
                        integer(r, 12), r.getString(13), r.getObject(14, UUID.class), r.getString(15), instant(r, 16),
                        instant(r, 17), instant(r, 18)));
            }
        }
        return out;
    }

    private static Instant instant(ResultSet r, int column) throws SQLException {
        Timestamp value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet r, int column) throws SQLException {
        int value = r.getInt(column);
        return r.wasNull() ? null : value;
    }

    private static void setInteger(PreparedStatement s, int index, Integer value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.INTEGER);
        } else {
            s.setInt(index, value);
        }
    }

    /** A new intent. */
    public record NewIntent(String notificationId, String dedupeKey, String orderId, String noticeType, String channel,
            String templateVersion, String body, String correlationId, UUID causationId, String traceparent) {}

    /** An intent claimed for delivery. */
    public record Claimed(String notificationId, String orderId, String noticeType, String channel, String templateVersion,
            String body, int attemptCount, String correlationId, UUID causationId, String traceparent) {}

    /** One provider call. */
    public record AttemptRecord(int attempt, Instant startedAt, String outcome, String failureCode, Integer httpStatus) {}

    /** A stored intent. */
    public record Row(String notificationId, String orderId, String noticeType, String channel, String templateVersion,
            String body, String status, int attemptCount, Instant nextAttemptAt, String failureCategory,
            String failureCode, Integer failureHttpStatus, String correlationId, UUID causationId, String traceparent,
            Instant createdAt, Instant updatedAt, Instant terminalAt) {}

    /** An event that reported an intent's fact. */
    public record SourceRow(UUID eventId, String eventType, Instant receivedAt) {}

    /** A recorded attempt. */
    public record AttemptRow(int attempt, Instant startedAt, Instant finishedAt, String outcome, String failureCode,
            Integer httpStatus) {}

    /** A delivery receipt. */
    public record ReceiptRow(String receiptId, String provider, String channel, String bodySha256, int bodyLength,
            Instant acceptedAt) {}

    /** Metric inputs. */
    public record Snapshot(long pending, double oldestPendingAgeSeconds, long outboxPending,
            double outboxOldestAgeSeconds) {}
}
