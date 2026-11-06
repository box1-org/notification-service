package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.box1.notification.domain.Fact;
import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.NoticeType;
import org.junit.jupiter.api.Test;

class NoticeCatalogTest {
    private static InboundEvent state(String to, String reason) {
        return new InboundEvent(UUID.randomUUID(), InboundEvent.STATE_CHANGED, "ord_7", Instant.now(), "c", null,
                "FULFILLMENT_REQUESTED", to, reason, null, null, null);
    }

    private static InboundEvent failed(boolean retryable, String reason) {
        return new InboundEvent(UUID.randomUUID(), InboundEvent.SHIPMENT_FAILED, "ord_7", Instant.now(), "c", null, null,
                null, reason, "ful_7", null, retryable);
    }

    private static Fact fact(InboundEvent event) {
        return NoticeCatalog.factFor(event).orElseThrow();
    }

    @Test
    void shippedStateChangeAndShipmentConfirmedAreOneFact() {
        InboundEvent confirmed = new InboundEvent(UUID.randomUUID(), InboundEvent.SHIPMENT_CONFIRMED, "ord_7", Instant.now(),
                "c", null, null, null, null, "ful_7", "shp_7", null);
        Fact a = fact(state("SHIPPED", "SHIPMENT_CONFIRMED"));
        Fact b = fact(confirmed);
        assertEquals(NoticeType.ORDER_SHIPPED, a.noticeType());
        assertEquals(a.dedupeKey(), b.dedupeKey());
        assertEquals(a.notificationId(), b.notificationId());
    }

    @Test
    void nonRetryableFailureAndShipmentFailedExceptionAreOneFact() {
        Fact a = fact(failed(false, "CARRIER_REJECTED"));
        Fact b = fact(state("EXCEPTION", "SHIPMENT_FAILED"));
        assertEquals(NoticeType.SHIPMENT_FAILED, a.noticeType());
        assertEquals(a.dedupeKey(), b.dedupeKey());
    }

    @Test
    void retryableFailureIsADelayNoticeKeyedByRequestAndReason() {
        Fact a = fact(failed(true, "CARRIER_TIMEOUT"));
        assertEquals(NoticeType.SHIPMENT_DELAYED, a.noticeType());
        assertEquals(a.dedupeKey(), fact(failed(true, "CARRIER_TIMEOUT")).dedupeKey());
        assertNotEquals(a.dedupeKey(), fact(failed(true, "SITE_CLOSED")).dedupeKey());
    }

    @Test
    void otherExceptionsAndRejectionsHaveTheirOwnNotices() {
        assertEquals(NoticeType.ORDER_EXCEPTION, fact(state("EXCEPTION", "RESERVATION_EXPIRED")).noticeType());
        assertEquals(NoticeType.ORDER_REJECTED, fact(state("REJECTED", "INSUFFICIENT_STOCK")).noticeType());
    }

    @Test
    void routineStatesAndOtherEventsGiveNoNotice() {
        for (String to : new String[] {"SUBMITTED", "RESERVED", "FULFILLMENT_REQUESTED", "COMPLETED", "CANCELLED"}) {
            assertTrue(NoticeCatalog.factFor(state(to, "X")).isEmpty(), to);
        }
        InboundEvent placed = new InboundEvent(UUID.randomUUID(), InboundEvent.ORDER_PLACED, "ord_7", Instant.now(), "c",
                null, null, null, null, null, null, null);
        assertTrue(NoticeCatalog.factFor(placed).isEmpty());
    }

    @Test
    void notificationIdIsStableAndShaped() {
        String id = fact(state("REJECTED", "X")).notificationId();
        assertTrue(id.matches("^ntf_[0-9a-f]{26}$"), id);
        assertEquals(id, fact(state("REJECTED", "OTHER")).notificationId());
    }
}
