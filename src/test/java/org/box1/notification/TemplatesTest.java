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
