package org.box1.notification.delivery;

import java.util.Map;
import java.util.regex.Pattern;

import org.box1.notification.Templates;

/**
 * Renders a notice as an operator email. The body is the stored, already-escaped template text plus allowlisted
 * identifiers. Header values come only from validated identifiers and a fixed title table, so no event value can add a
 * header or a recipient.
 */
public final class OperatorMail {
    private static final Pattern NOTIFICATION_ID = Pattern.compile("^ntf_[0-9a-f]{26}$");
    private static final Pattern ORDER_ID = Pattern.compile("^ord_[A-Za-z0-9_-]{1,124}$");
    private static final Pattern CHANNEL = Pattern.compile("^ops-[a-z0-9-]{1,40}$");
    private static final Pattern TEMPLATE_VERSION = Pattern.compile("^[A-Z_]{1,40}@v[0-9]{1,4}$");
    private static final Map<String, String> TITLES = Map.of(
            "ORDER_SHIPPED", "Order shipped",
            "SHIPMENT_DELAYED", "Shipment delayed",
            "SHIPMENT_FAILED", "Shipment failed",
            "ORDER_EXCEPTION", "Order exception",
            "ORDER_REJECTED", "Order rejected");

    private OperatorMail() {}

    /** One rendered message. */
    public record Rendered(String recipient, String subject, String text, String messageId, Map<String, String> headers) {}

    public static Rendered render(Delivery delivery, String recipientDomain) {
        String id = checked(NOTIFICATION_ID, delivery.notificationId(), "notification ID");
        String order = checked(ORDER_ID, delivery.orderId(), "order ID");
        String channel = checked(CHANNEL, delivery.channel(), "channel");
        String version = checked(TEMPLATE_VERSION, delivery.templateVersion(), "template version");
        String title = TITLES.get(delivery.noticeType());
        if (title == null) {
            throw new IllegalArgumentException("Unknown notice type");
        }
        String subject = "[Box1] " + title + " — " + order;
        String text = delivery.body() + "\n\n"
                + "Notice type: " + delivery.noticeType() + "\n"
                + "Order: " + order + "\n"
                + "Notification ID: " + id + "\n"
                + "Template: " + version + "\n"
                + "Correlation ID: " + Templates.escape(delivery.correlationId()) + "\n\n"
                + "Box1 operator notice. It carries internal operational identifiers only.\n";
        return new Rendered(channel + "@" + recipientDomain, subject, text, "<" + id + "@" + recipientDomain + ">",
                Map.of("X-Box1-Notification-Id", id, "X-Box1-Notice-Type", delivery.noticeType(), "X-Box1-Order-Id", order,
                        "X-Box1-Template-Version", version));
    }

    private static String checked(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + what);
        }
        return value;
    }
}
