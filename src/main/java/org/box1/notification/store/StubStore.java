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

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** The stub provider's receipts, active fault and append-only fault audit. Expiry uses database time. */
@ApplicationScoped
public class StubStore {
    private final DataSource dataSource;

    public StubStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<String> receiptFor(String notificationId) {
        String sql = "SELECT receipt_id FROM delivery_receipt WHERE notification_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, notificationId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /**
     * Write the receipt once, and only while the notification is not terminal. The notification row is locked, so a late
     * call (one the worker already timed out) cannot add a receipt to a notification recorded as FAILED. A repeated call
     * returns the existing receipt; a late call after a terminal status returns null.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public String writeReceipt(String receiptId, String notificationId, String channel, String bodySha256, int bodyLength) {
        String lock = "SELECT status FROM notification WHERE notification_id = ? FOR UPDATE";
        String insert = "INSERT INTO delivery_receipt (receipt_id, notification_id, provider, channel, body_sha256, body_length)"
                + " SELECT ?, ?, 'stub', ?, ?, ? WHERE EXISTS (SELECT 1 FROM notification WHERE notification_id = ?"
                + " AND status IN ('PENDING', 'RETRY_SCHEDULED')) ON CONFLICT (notification_id) DO NOTHING";
        String select = "SELECT receipt_id FROM delivery_receipt WHERE notification_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement l = c.prepareStatement(lock);
                PreparedStatement s = c.prepareStatement(insert)) {
            l.setString(1, notificationId);
            l.executeQuery().close();
            s.setString(1, receiptId);
            s.setString(2, notificationId);
            s.setString(3, channel);
            s.setString(4, bodySha256);
            s.setInt(5, bodyLength);
            s.setString(6, notificationId);
            s.executeUpdate();
            try (PreparedStatement q = c.prepareStatement(select)) {
                q.setString(1, notificationId);
                try (ResultSet r = q.executeQuery()) {
                    return r.next() ? r.getString(1) : null;
                }
            }
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /**
     * Take the active fault for one provider call. An expired fault is removed and audited as EXPIRED; a fault with a
     * call budget is decremented and, once spent, removed and audited as EXHAUSTED.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Fault> takeFault() {
        String select = "SELECT mode, delay_millis, fail_remaining, expires_at, set_by, set_at, expires_at <= now()"
                + " FROM stub_fault WHERE id = 1 FOR UPDATE";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(select)) {
            Fault fault;
            boolean expired;
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                fault = fault(r);
                expired = r.getBoolean(7);
            }
            if (expired) {
                remove(c, "EXPIRED", fault);
                return Optional.empty();
            }
            if (fault.failRemaining() != null) {
                if (fault.failRemaining() <= 0) {
                    remove(c, "EXHAUSTED", fault);
                    return Optional.empty();
                }
                try (PreparedStatement u = c.prepareStatement(
                        "UPDATE stub_fault SET fail_remaining = fail_remaining - 1 WHERE id = 1")) {
                    u.executeUpdate();
                }
            }
            return Optional.of(fault);
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Read the active fault without consuming it. Expired faults are reported as absent. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Fault> activeFault() {
        String sql = "SELECT mode, delay_millis, fail_remaining, expires_at, set_by, set_at FROM stub_fault"
                + " WHERE id = 1 AND expires_at > now() AND (fail_remaining IS NULL OR fail_remaining > 0)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql);
                ResultSet r = s.executeQuery()) {
            return r.next() ? Optional.of(fault(r)) : Optional.empty();
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Replace the active fault and audit it, in one transaction. Returns the expiry in database time. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Instant setFault(String mode, int ttlSeconds, int delayMillis, Integer failCount, String actor, String subject,
            String namespace) {
        String upsert = "INSERT INTO stub_fault (id, mode, delay_millis, fail_remaining, expires_at, set_by, set_at)"
                + " VALUES (1, ?, ?, ?, now() + make_interval(secs => ?), ?, now())"
                + " ON CONFLICT (id) DO UPDATE SET mode = EXCLUDED.mode, delay_millis = EXCLUDED.delay_millis,"
                + " fail_remaining = EXCLUDED.fail_remaining, expires_at = EXCLUDED.expires_at, set_by = EXCLUDED.set_by,"
                + " set_at = EXCLUDED.set_at RETURNING expires_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(upsert)) {
            s.setString(1, mode);
            s.setInt(2, delayMillis);
            if (failCount == null) {
                s.setNull(3, Types.INTEGER);
            } else {
                s.setInt(3, failCount);
            }
            s.setInt(4, ttlSeconds);
            s.setString(5, actor);
            Instant expires;
            try (ResultSet r = s.executeQuery()) {
                r.next();
                expires = r.getTimestamp(1).toInstant();
            }
            audit(c, actor, subject, "SET", mode, ttlSeconds, "ACCEPTED", namespace);
            return expires;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Clear the active fault and audit it. True if one was active. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean clearFault(String actor, String subject, String namespace) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement s = c.prepareStatement("DELETE FROM stub_fault WHERE id = 1 RETURNING mode")) {
            String mode = null;
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    mode = r.getString(1);
                }
            }
            audit(c, actor, subject, "CLEAR", mode, null, mode == null ? "NO_ACTIVE_FAULT" : "ACCEPTED", namespace);
            return mode != null;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Remove an expired fault, recording the expiry. Run by a sweep so the audit shows when a fault ended. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean sweepExpired() {
        String sql = "SELECT mode, delay_millis, fail_remaining, expires_at, set_by, set_at FROM stub_fault"
                + " WHERE id = 1 AND expires_at <= now() FOR UPDATE";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            Fault fault;
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return false;
                }
                fault = fault(r);
            }
            remove(c, "EXPIRED", fault);
            return true;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    /** Audit a refused request in its own transaction. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void auditRefusal(String actor, String subject, String action, String mode, Integer ttlSeconds, String outcome,
            String namespace) {
        try (Connection c = dataSource.getConnection()) {
            audit(c, actor, subject, action, mode, ttlSeconds, outcome, namespace);
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AuditRow> audit(int limit) {
        String sql = "SELECT id, at, actor, action, mode, ttl_seconds, outcome, namespace FROM stub_fault_audit"
                + " ORDER BY id DESC LIMIT ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, limit);
            List<AuditRow> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    int ttl = r.getInt(6);
                    Integer ttlValue = r.wasNull() ? null : ttl;
                    out.add(new AuditRow(r.getLong(1), r.getTimestamp(2).toInstant(), r.getString(3), r.getString(4),
                            r.getString(5), ttlValue, r.getString(7), r.getString(8)));
                }
            }
            return out;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    private static void remove(Connection c, String action, Fault fault) throws SQLException {
        try (PreparedStatement d = c.prepareStatement("DELETE FROM stub_fault WHERE id = 1")) {
            d.executeUpdate();
        }
        audit(c, "system", null, action, fault.mode(), null, "ENDED", null);
    }

    private static void audit(Connection c, String actor, String subject, String action, String mode, Integer ttlSeconds,
            String outcome, String namespace) throws SQLException {
        String sql = "INSERT INTO stub_fault_audit (actor, actor_subject, action, mode, ttl_seconds, outcome, namespace)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, actor);
            s.setString(2, subject);
            s.setString(3, action);
            s.setString(4, mode);
            if (ttlSeconds == null) {
                s.setNull(5, Types.INTEGER);
            } else {
                s.setInt(5, ttlSeconds);
            }
            s.setString(6, outcome);
            s.setString(7, namespace);
            s.executeUpdate();
        }
    }

    private static Fault fault(ResultSet r) throws SQLException {
        int remaining = r.getInt(3);
        Integer failRemaining = r.wasNull() ? null : remaining;
        Timestamp expires = r.getTimestamp(4);
        return new Fault(r.getString(1), r.getInt(2), failRemaining, expires.toInstant(), r.getString(5),
                r.getTimestamp(6).toInstant());
    }

    /** The stub's active fault. */
    public record Fault(String mode, int delayMillis, Integer failRemaining, Instant expiresAt, String setBy, Instant setAt) {}

    /** One fault-control audit entry. */
    public record AuditRow(long id, Instant at, String actor, String action, String mode, Integer ttlSeconds, String outcome,
            String namespace) {}
}
