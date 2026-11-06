package org.box1.notification.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.PoisonEventException;

/**
 * Checks inbound records against the box1-ops-contracts v1 rules and keeps only allowlisted fields (decision D-012).
 * Failure messages name a field, never a value, because they reach dead-letter headers. Unknown payload fields are
 * tolerated as additive v1 changes.
 */
@ApplicationScoped
public class EventParser {
    public static final Set<String> ORDER_TOPIC_TYPES = Set.of(InboundEvent.ORDER_PLACED,
            InboundEvent.FULFILLMENT_REQUESTED, InboundEvent.STATE_CHANGED);
    public static final Set<String> FULFILLMENT_TOPIC_TYPES = Set.of(InboundEvent.SHIPMENT_CONFIRMED,
            InboundEvent.SHIPMENT_FAILED);

    private static final Pattern ORDER = Pattern.compile("^ord_[A-Za-z0-9_-]{1,124}$");
    private static final Pattern FULFILLMENT = Pattern.compile("^ful_[A-Za-z0-9_-]{1,124}$");
    private static final Pattern SHIPMENT = Pattern.compile("^shp_[A-Za-z0-9_-]{1,124}$");
    private static final Set<String> STATES = Set.of("SUBMITTED", "RESERVED", "FULFILLMENT_REQUESTED", "SHIPPED",
            "COMPLETED", "REJECTED", "EXCEPTION", "CANCELLED");
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final Pattern BOUNDED_ID = Pattern.compile("^[^\\p{Cntrl}]{1,128}$");
    private static final Pattern UUID_FORM = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ObjectMapper mapper;

    public EventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parse a record value, or throw {@link PoisonEventException}. */
    public InboundEvent parse(String value, Set<String> allowedTypes) {
        JsonNode root = read(value);
        JsonNode version = root.path("schemaVersion");
        if (!version.isInt() || version.intValue() != 1) {
            throw new PoisonEventException("Unsupported schemaVersion");
        }
        UUID eventId = uuid(root);
        String type = text(root, "eventType");
        if (!allowedTypes.contains(type)) {
            throw new PoisonEventException("Unsupported eventType");
        }
        String aggregateId = bounded(root, "aggregateId");
        Instant occurredAt = instant(root, "occurredAt");
        String correlationId = bounded(root, "correlationId");
        bounded(root, "causationId");
        String traceparent = root.hasNonNull("traceparent") ? matching(root, "traceparent", TRACEPARENT) : null;
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new PoisonEventException("Missing payload");
        }
        // orderId is an opaque order identifier, not a customer identifier; it is the only field read from every payload.
        String orderId = matching(payload, "orderId", ORDER);
        if (!orderId.equals(aggregateId)) {
            throw new PoisonEventException("aggregateId does not match orderId");
        }
        String fromState = null;
        String toState = null;
        String reasonCode = null;
        String fulfillmentRequestId = null;
        String shipmentId = null;
        Boolean retryable = null;
        switch (type) {
            case InboundEvent.ORDER_PLACED, InboundEvent.FULFILLMENT_REQUESTED -> {
                // Envelope only. The order.placed payload carries customerId and is deliberately not read (D-012).
            }
            case InboundEvent.STATE_CHANGED -> {
                fromState = state(payload, "fromState");
                toState = state(payload, "toState");
                reasonCode = matching(payload, "reasonCode", REASON);
                instant(payload, "changedAt");
            }
            case InboundEvent.SHIPMENT_CONFIRMED -> {
                fulfillmentRequestId = matching(payload, "fulfillmentRequestId", FULFILLMENT);
                shipmentId = matching(payload, "shipmentId", SHIPMENT);
                instant(payload, "shippedAt");
            }
            case InboundEvent.SHIPMENT_FAILED -> {
                fulfillmentRequestId = matching(payload, "fulfillmentRequestId", FULFILLMENT);
                reasonCode = matching(payload, "reasonCode", REASON);
                if (!payload.path("retryable").isBoolean()) {
                    throw new PoisonEventException("Missing retryable");
                }
                retryable = payload.get("retryable").asBoolean();
            }
            default -> throw new PoisonEventException("Unsupported eventType");
        }
        return new InboundEvent(eventId, type, orderId, occurredAt, correlationId, traceparent, fromState, toState,
                reasonCode, fulfillmentRequestId, shipmentId, retryable);
    }

    private JsonNode read(String value) {
        if (value == null) {
            throw new PoisonEventException("Empty record value");
        }
        try {
            JsonNode root = mapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw new PoisonEventException("Record value is not a JSON object");
            }
            return root;
        } catch (JsonProcessingException error) {
            throw new PoisonEventException("Record value is not valid JSON");
        }
    }

    private static String state(JsonNode node, String field) {
        String value = text(node, field);
        if (!STATES.contains(value)) {
            throw new PoisonEventException("Invalid " + field);
        }
        return value;
    }

    private static UUID uuid(JsonNode root) {
        String value = text(root, "eventId");
        if (!UUID_FORM.matcher(value).matches()) {
            throw new PoisonEventException("Invalid eventId");
        }
        return UUID.fromString(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new PoisonEventException("Missing " + field);
        }
        return value.asText();
    }

    private static String bounded(JsonNode node, String field) {
        return matching(node, field, BOUNDED_ID);
    }

    private static String matching(JsonNode node, String field, Pattern pattern) {
        String value = text(node, field);
        if (!pattern.matcher(value).matches()) {
            throw new PoisonEventException("Invalid " + field);
        }
        return value;
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException error) {
            throw new PoisonEventException("Invalid " + field);
        }
    }
}
