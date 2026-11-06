package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.NoticeType;
import org.junit.jupiter.api.Test;

class TemplatesTest {
    private static InboundEvent event(String reason, String fulfillment) {
        return new InboundEvent(UUID.randomUUID(), InboundEvent.SHIPMENT_FAILED, "ord_1", Instant.now(), "c", null, null,
                null, reason, fulfillment, null, true);
    }

    @Test
    void escapesMarkupControlCharactersAndChatSyntax() {
        String hostile = "X<script>alert('x')</script>\r\n| col | [link](http://evil) `code` &amp; ‮";
        String text = Templates.render(NoticeType.SHIPMENT_DELAYED, event(hostile, "ful_1"));
        assertFalse(text.contains("<script>"), text);
        assertFalse(text.contains("\n") || text.contains("\r") || text.contains(" ") || text.contains("‮"), text);
        assertFalse(text.contains("|") || text.contains("`") || text.contains("[link]"), text);
        assertTrue(text.contains("&lt;script&gt;"), text);
        assertTrue(text.contains("&amp;amp;"), text);
    }

    @Test
    void capsLongValues() {
        String text = Templates.render(NoticeType.SHIPMENT_DELAYED, event("A".repeat(10_000), "ful_1"));
        assertTrue(text.length() < 600, "length " + text.length());
    }

    @Test
    void cutsOnCodePointsSoNoSurrogateIsSplit() {
        String text = Templates.escape("a".repeat(127) + "\uD83D\uDE00" + "b".repeat(10));
        assertTrue(text.endsWith("\uD83D\uDE00…"), text);
    }

    @Test
    void factsReportedByTwoEventsRenderTheSameWhicheverArrivesFirst() {
        InboundEvent confirmed = new InboundEvent(UUID.randomUUID(), InboundEvent.SHIPMENT_CONFIRMED, "ord_1", Instant.now(),
                "c", null, null, null, null, "ful_1", "shp_1", null);
        InboundEvent shipped = new InboundEvent(UUID.randomUUID(), InboundEvent.STATE_CHANGED, "ord_1", Instant.now(), "c",
                null, "FULFILLMENT_REQUESTED", "SHIPPED", "OK", null, null, null);
        assertEquals(Templates.render(NoticeType.ORDER_SHIPPED, confirmed), Templates.render(NoticeType.ORDER_SHIPPED, shipped));
        InboundEvent failed = new InboundEvent(UUID.randomUUID(), InboundEvent.SHIPMENT_FAILED, "ord_1", Instant.now(), "c",
                null, null, null, "CARRIER_REJECTED", "ful_1", null, false);
        InboundEvent exception = new InboundEvent(UUID.randomUUID(), InboundEvent.STATE_CHANGED, "ord_1", Instant.now(), "c",
                null, "FULFILLMENT_REQUESTED", "EXCEPTION", "SHIPMENT_FAILED", null, null, null);
        assertEquals(Templates.render(NoticeType.SHIPMENT_FAILED, failed), Templates.render(NoticeType.SHIPMENT_FAILED, exception));
    }

    @Test
    void rendersOnlyAllowlistedFieldsWithAVersion() {
        assertEquals("SHIPMENT_DELAYED@v1", Templates.version(NoticeType.SHIPMENT_DELAYED));
        String text = Templates.render(NoticeType.SHIPMENT_DELAYED, event("CARRIER_TIMEOUT", "ful_9"));
        assertEquals("Shipment for order ord_1 is delayed: fulfillment request ful_9 failed with retryable reason "
                + "CARRIER_TIMEOUT. The order state is unchanged and the fulfillment adapter will retry.", text);
        for (NoticeType type : NoticeType.values()) {
            assertFalse(Templates.render(type, event("R", "ful_1")).contains("{"), type.name());
        }
    }
}
