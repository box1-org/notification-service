package org.box1.notification;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds v1 envelopes for tests. The values are synthetic. */
public final class Events {
    public static final ObjectMapper JSON = new ObjectMapper();
    /** A customer identifier that must never appear in anything this service writes. */
    public static final String SENTINEL_CUSTOMER = "cust_SENTINEL_7f3a9c";

    private Events() {}

    public static ObjectNode envelope(UUID eventId, String type, String orderId, Instant occurredAt, ObjectNode payload) {
        ObjectNode root = JSON.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", type);
        root.put("schemaVersion", 1);
        root.put("aggregateId", orderId);
        root.put("occurredAt", occurredAt.toString());
        root.put("correlationId", "corr-" + orderId);
        root.put("causationId", "cause-" + eventId);
        root.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        root.set("payload", payload);
        return root;
    }

    public static ObjectNode stateChanged(String orderId, String from, String to, String reason) {
        ObjectNode p = JSON.createObjectNode().put("orderId", orderId).put("fromState", from).put("toState", to)
                .put("reasonCode", reason).put("changedAt", Instant.now().toString());
        return envelope(UUID.randomUUID(), "order.state-changed.v1", orderId, Instant.now(), p);
    }

    public static ObjectNode shipmentConfirmed(String orderId) {
        ObjectNode p = JSON.createObjectNode().put("orderId", orderId).put("fulfillmentRequestId", "ful_" + orderId.substring(4))
                .put("shipmentId", "shp_" + orderId.substring(4)).put("shippedAt", Instant.now().toString());
        return envelope(UUID.randomUUID(), "shipment.confirmed.v1", orderId, Instant.now(), p);
    }

    public static ObjectNode shipmentFailed(String orderId, boolean retryable, String reason) {
        ObjectNode p = JSON.createObjectNode().put("orderId", orderId).put("fulfillmentRequestId", "ful_" + orderId.substring(4))
                .put("retryable", retryable).put("reasonCode", reason);
        return envelope(UUID.randomUUID(), "shipment.failed.v1", orderId, Instant.now(), p);
    }

    public static ObjectNode orderPlaced(String orderId) {
        ObjectNode p = JSON.createObjectNode().put("orderId", orderId).put("customerId", SENTINEL_CUSTOMER)
                .put("sku", "SKU-42").put("quantity", 1).put("siteId", "site_nyc");
        return envelope(UUID.randomUUID(), "order.placed.v1", orderId, Instant.now(), p);
    }
}
