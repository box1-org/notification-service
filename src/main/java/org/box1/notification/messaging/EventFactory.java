package org.box1.notification.messaging;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.NotificationStore;

/**
 * Builds v1 envelopes for notification.delivered.v1 and notification.failed.v1 (box1-ops-contracts). Payloads hold
 * identifiers, codes and counts only.
 */
@ApplicationScoped
public class EventFactory {
    public static final String DELIVERED = "notification.delivered.v1";
    public static final String FAILED = "notification.failed.v1";

    private final ObjectMapper mapper;

    public EventFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A built event. */
    public record Built(UUID eventId, String eventType, String json) {}

    public Built delivered(NotificationStore.Claimed intent, int attempts, String provider, String receiptId, Instant at) {
        ObjectNode payload = identity(intent, attempts, provider);
        payload.put("receiptId", receiptId);
        payload.put("deliveredAt", format(at));
        return envelope(DELIVERED, intent, payload, at);
    }

    public Built failed(NotificationStore.Claimed intent, int attempts, String provider, String category, String code,
            Integer httpStatus, Instant at) {
        ObjectNode payload = identity(intent, attempts, provider);
        payload.put("failureCategory", category);
        payload.put("failureCode", code);
        if (httpStatus == null) {
            payload.putNull("httpStatus");
        } else {
            payload.put("httpStatus", httpStatus);
        }
        payload.put("failedAt", format(at));
        return envelope(FAILED, intent, payload, at);
    }

    private ObjectNode identity(NotificationStore.Claimed intent, int attempts, String provider) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("notificationId", intent.notificationId());
        payload.put("orderId", intent.orderId());
        payload.put("noticeType", intent.noticeType());
        payload.put("channel", intent.channel());
        payload.put("templateVersion", intent.templateVersion());
        payload.put("attemptCount", attempts);
        payload.put("provider", provider);
        return payload;
    }

    private Built envelope(String type, NotificationStore.Claimed intent, ObjectNode payload, Instant at) {
        UUID eventId = UUID.randomUUID();
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", type);
        root.put("schemaVersion", 1);
        root.put("aggregateId", intent.orderId());
        root.put("occurredAt", format(at));
        root.put("correlationId", intent.correlationId());
        root.put("causationId", intent.causationId().toString());
        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            root.put("traceparent", "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex());
        }
        root.set("payload", payload);
        return new Built(eventId, type, root.toString());
    }

    private static String format(Instant at) {
        return at.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
