package org.box1.notification;

import java.util.Optional;

import org.box1.notification.domain.Fact;
import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.NoticeType;

/** Maps events to business facts (decisions D-002 and D-003). An empty result means no notice. */
public final class NoticeCatalog {
    private NoticeCatalog() {}

    public static Optional<Fact> factFor(InboundEvent event) {
        String order = event.orderId();
        return switch (event.eventType()) {
            case InboundEvent.SHIPMENT_CONFIRMED -> Optional.of(fact(NoticeType.ORDER_SHIPPED, order, order));
            case InboundEvent.SHIPMENT_FAILED -> Boolean.TRUE.equals(event.retryable())
                    ? Optional.of(fact(NoticeType.SHIPMENT_DELAYED, order,
                            order + ":" + event.fulfillmentRequestId() + ":" + event.reasonCode()))
                    : Optional.of(fact(NoticeType.SHIPMENT_FAILED, order, order));
            case InboundEvent.STATE_CHANGED -> stateChange(event);
            default -> Optional.empty();
        };
    }

    private static Optional<Fact> stateChange(InboundEvent event) {
        String order = event.orderId();
        return switch (event.toState()) {
            case "SHIPPED" -> Optional.of(fact(NoticeType.ORDER_SHIPPED, order, order));
            case "REJECTED" -> Optional.of(fact(NoticeType.ORDER_REJECTED, order, order));
            case "EXCEPTION" -> "SHIPMENT_FAILED".equals(event.reasonCode())
                    ? Optional.of(fact(NoticeType.SHIPMENT_FAILED, order, order))
                    : Optional.of(fact(NoticeType.ORDER_EXCEPTION, order, order));
            // COMPLETED follows SHIPPED immediately under the completion policy; a second notice would be noise (D-002).
            default -> Optional.empty();
        };
    }

    private static Fact fact(NoticeType type, String orderId, String qualifier) {
        return new Fact(type, orderId, type.name() + ":" + qualifier);
    }
}
