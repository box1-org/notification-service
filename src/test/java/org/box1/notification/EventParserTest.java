package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.PoisonEventException;
import org.box1.notification.messaging.EventParser;
import org.junit.jupiter.api.Test;

class EventParserTest {
    private final EventParser parser = new EventParser(new ObjectMapper());

    private void poison(String value, String expectedMessage) {
        PoisonEventException error = assertThrows(PoisonEventException.class,
                () -> parser.parse(value, EventParser.ORDER_TOPIC_TYPES));
        assertEquals(expectedMessage, error.getMessage());
    }

    @Test
    void poisonInputsAreRejectedWithValueFreeMessages() {
        poison(null, "Empty record value");
        poison("{not json", "Record value is not valid JSON");
        poison("[1,2]", "Record value is not a JSON object");
        ObjectNode e = Events.stateChanged("ord_1", "FULFILLMENT_REQUESTED", "SHIPPED", "OK");
        poison(e.deepCopy().put("schemaVersion", 2).toString(), "Unsupported schemaVersion");
        poison(e.deepCopy().put("eventType", "shipment.confirmed.v1").toString(), "Unsupported eventType");
        poison(e.deepCopy().put("aggregateId", "ord_2").toString(), "aggregateId does not match orderId");
        ObjectNode badState = e.deepCopy();
        ((ObjectNode) badState.get("payload")).put("toState", "SHIPPED\n<script>");
        poison(badState.toString(), "Invalid toState");
        ObjectNode badId = e.deepCopy();
        ((ObjectNode) badId.get("payload")).put("orderId", "customer@example.com");
        badId.put("aggregateId", "customer@example.com");
        poison(badId.toString(), "Invalid orderId");
    }

    @Test
    void poisonMessagesNeverEchoValues() {
        ObjectNode e = Events.stateChanged("ord_1", "FULFILLMENT_REQUESTED", "SHIPPED", "OK");
        ((ObjectNode) e.get("payload")).put("reasonCode", "SECRET_VALUE lower");
        PoisonEventException error = assertThrows(PoisonEventException.class,
                () -> parser.parse(e.toString(), EventParser.ORDER_TOPIC_TYPES));
        assertTrue(!error.getMessage().contains("SECRET_VALUE"), error.getMessage());
    }

    @Test
    void orderPlacedIsEnvelopeOnlyAndItsCustomerFieldIsNeverRead() {
        ObjectNode placed = Events.orderPlaced("ord_5");
        // Even a malformed customerId does not matter, because the field is never read.
        ((ObjectNode) placed.get("payload")).put("customerId", "\u0000 anything");
        InboundEvent event = parser.parse(placed.toString(), EventParser.ORDER_TOPIC_TYPES);
        assertTrue(event.envelopeOnly());
        String text = event.toString();
        assertTrue(!text.contains(Events.SENTINEL_CUSTOMER) && !text.contains("anything"), text);
        assertTrue(Arrays.stream(InboundEvent.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase(java.util.Locale.ROOT).contains("customer")));
    }

    @Test
    void shipmentFailedNeedsABooleanRetryable() {
        ObjectNode failed = Events.shipmentFailed("ord_3", true, "CARRIER_TIMEOUT");
        InboundEvent event = parser.parse(failed.toString(), EventParser.FULFILLMENT_TOPIC_TYPES);
        assertEquals(Boolean.TRUE, event.retryable());
        assertNull(event.toState());
        ((ObjectNode) failed.get("payload")).put("retryable", "true");
        assertThrows(PoisonEventException.class, () -> parser.parse(failed.toString(), EventParser.FULFILLMENT_TOPIC_TYPES));
    }
}
