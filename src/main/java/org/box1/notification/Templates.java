package org.box1.notification;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.NoticeType;

/**
 * Versioned plain-text templates (decision D-002). Only allowlisted event fields are substituted, and every value is
 * escaped, so a hostile value cannot add lines, markup or table cells to a notice.
 */
public final class Templates {
    public static final int VERSION = 1;
    private static final int MAX_VALUE = 128;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");
    private static final Map<NoticeType, String> V1 = Map.of(
            NoticeType.ORDER_SHIPPED, "Order {orderId} shipped.{shipmentDetail}",
            NoticeType.SHIPMENT_DELAYED, "Shipment for order {orderId} is delayed: fulfillment request "
                    + "{fulfillmentRequestId} failed with retryable reason {reasonCode}. The order state is unchanged and "
                    + "the fulfillment adapter will retry.",
            NoticeType.SHIPMENT_FAILED, "Shipment for order {orderId} failed and the order needs manual reconciliation. "
                    + "Reason: {reasonCode}.",
            NoticeType.ORDER_EXCEPTION, "Order {orderId} moved from {fromState} to EXCEPTION with reason {reasonCode} "
                    + "and needs manual reconciliation.",
            NoticeType.ORDER_REJECTED, "Order {orderId} was rejected with reason {reasonCode}.");

    private Templates() {}

    public static String version(NoticeType type) {
        return type.name() + "@v" + VERSION;
    }

    public static String render(NoticeType type, InboundEvent event) {
        Map<String, String> values = new HashMap<>();
        values.put("orderId", event.orderId());
        values.put("fulfillmentRequestId", event.fulfillmentRequestId());
        values.put("reasonCode", event.reasonCode());
        values.put("fromState", event.fromState());
        values.put("shipmentDetail", event.shipmentId() == null ? ""
                : " Shipment " + escape(event.shipmentId()) + " for fulfillment request "
                        + escape(event.fulfillmentRequestId()) + ".");
        Matcher matcher = PLACEHOLDER.matcher(V1.get(type));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            String rendered = "shipmentDetail".equals(name) ? value : escape(value == null ? "unknown" : value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Neutralize control characters and characters with meaning in common chat or markup formats; cap the length. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        String cut = value.length() > MAX_VALUE ? value.substring(0, MAX_VALUE) + "…" : value;
        StringBuilder out = new StringBuilder(cut.length());
        cut.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '`' -> out.append("&#96;");
                case '|' -> out.append("&#124;");
                case '[' -> out.append("&#91;");
                case ']' -> out.append("&#93;");
                default -> {
                    if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                            || cp == 0x2028 || cp == 0x2029) {
                        out.append('�');
                    } else {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.toString();
    }
}
