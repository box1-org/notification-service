package org.box1.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The allowlisted fields of a consumed event. Nothing else from a payload is kept, and {@code order.placed.v1}
 * payloads (the only ones with a customer identifier) are never read.
 */
public record InboundEvent(UUID eventId, String eventType, String orderId, Instant occurredAt, String correlationId,
        String traceparent, String fromState, String toState, String reasonCode, String fulfillmentRequestId,
        String shipmentId, Boolean retryable) {
    public static final String ORDER_PLACED = "order.placed.v1";
    public static final String FULFILLMENT_REQUESTED = "fulfillment.requested.v1";
    public static final String STATE_CHANGED = "order.state-changed.v1";
    public static final String SHIPMENT_CONFIRMED = "shipment.confirmed.v1";
    public static final String SHIPMENT_FAILED = "shipment.failed.v1";

    /** Order and fulfillment-request events this service does not turn into notices; only their envelope is checked. */
    public boolean envelopeOnly() {
        return ORDER_PLACED.equals(eventType) || FULFILLMENT_REQUESTED.equals(eventType);
    }
}
