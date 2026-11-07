package org.box1.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.box1.notification.store.NotificationStore;
import org.box1.notification.store.StubStore;

/** API response shapes. They hold IDs, codes, counts and the rendered operator notice; never customer data. */
public final class Views {
    private Views() {}

    /** A list of notifications for one order. */
    public record NotificationList(String orderId, int count, List<NotificationView> notifications) {}

    /** One notification. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NotificationView(String notificationId, String orderId, String noticeType, String channel,
            String templateVersion, String text, String status, int attemptCount, Instant nextAttemptAt, Failure failure,
            String correlationId, UUID causationId, String traceparent, Instant createdAt, Instant updatedAt,
            Instant terminalAt, List<Source> sources, List<Attempt> attempts, Receipt receipt) {

        static NotificationView of(NotificationStore.Row row, List<NotificationStore.SourceRow> sources,
                List<NotificationStore.AttemptRow> attempts, NotificationStore.ReceiptRow receipt) {
            Failure failure = row.failureCategory() == null ? null
                    : new Failure(row.failureCategory(), row.failureCode(), row.failureHttpStatus());
            return new NotificationView(row.notificationId(), row.orderId(), row.noticeType(), row.channel(),
                    row.templateVersion(), row.body(), row.status(), row.attemptCount(), row.nextAttemptAt(), failure,
                    row.correlationId(), row.causationId(), row.traceparent(), row.createdAt(), row.updatedAt(),
                    row.terminalAt(),
                    sources.stream().map(s -> new Source(s.eventId(), s.eventType(), s.receivedAt())).toList(),
                    attempts.stream().map(a -> new Attempt(a.attempt(), a.startedAt(), a.finishedAt(), a.outcome(),
                            a.failureCode(), a.httpStatus())).toList(),
                    receipt == null ? null : new Receipt(receipt.receiptId(), receipt.provider(), receipt.channel(),
                            receipt.bodySha256(), receipt.bodyLength(), receipt.acceptedAt(), receipt.messageId(),
                            receipt.recipient()));
        }
    }

    /** Structured, sanitized failure reason. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(String category, String code, Integer httpStatus) {}

    /** An event that reported the fact. */
    public record Source(UUID eventId, String eventType, Instant receivedAt) {}

    /** One provider call. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attempt(int attempt, Instant startedAt, Instant finishedAt, String outcome, String failureCode,
            Integer httpStatus) {}

    /**
     * Redacted receipt: the body's hash and length, not the body. For {@code provider=mailhog}, {@code messageId} and
     * {@code recipient} find the message in MailHog; both are null for the stub.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(String receiptId, String provider, String channel, String bodySha256, int bodyLength,
            Instant acceptedAt, String messageId, String recipient) {}

    /** The delivery provider and its fault control, as operators see them. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StubState(String provider, ActiveFault activeFault, FaultControl faultControl) {}

    /** The active delivery fault. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActiveFault(String mode, int delayMillis, Integer remainingFailures, Instant expiresAt, String setBy,
            Instant setAt) {
        static ActiveFault of(StubStore.Fault fault) {
            return new ActiveFault(fault.mode(), fault.delayMillis(), fault.failRemaining(), fault.expiresAt(),
                    fault.setBy(), fault.setAt());
        }
    }

    /** Whether fault control is open here, and why not. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FaultControl(boolean open, String refusal, String namespace, int maxTtlSeconds) {}

    /** A fault-control request. */
    public record FaultRequest(String mode, Integer ttlSeconds, Integer delayMillis, Integer failCount) {}

    /** The audit trail. */
    public record Audit(List<StubStore.AuditRow> entries) {}
}
