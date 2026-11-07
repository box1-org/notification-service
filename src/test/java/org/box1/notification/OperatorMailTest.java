package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.box1.notification.delivery.Delivery;
import org.box1.notification.delivery.MailHogProviderAccess;
import org.box1.notification.delivery.OperatorMail;
import org.box1.notification.delivery.ProviderResult;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;

/** Rendering and SMTP outcome mapping for the MailHog provider; no container needed. */
class OperatorMailTest {
    private static final String ID = "ntf_0123456789abcdef0123456789";

    private static Delivery delivery(String type, String channel, String body, String correlation) {
        return new Delivery(ID, "ord_123", type, channel, type + "@v1", body, correlation);
    }

    @Test
    void rendersTheOperatorMailboxSubjectAndAllowlistedFields() {
        OperatorMail.Rendered mail = OperatorMail.render(delivery("SHIPMENT_DELAYED", "ops-fulfillment",
                "Shipment for order ord_123 is delayed.", "corr-1"), "box1.local");
        assertEquals("ops-fulfillment@box1.local", mail.recipient());
        assertEquals("[Box1] Shipment delayed — ord_123", mail.subject());
        assertEquals("<" + ID + "@box1.local>", mail.messageId());
        assertTrue(mail.text().startsWith("Shipment for order ord_123 is delayed.\n\n"));
        assertTrue(mail.text().contains("Notice type: SHIPMENT_DELAYED\n"));
        assertTrue(mail.text().contains("Notification ID: " + ID + "\n"));
        assertTrue(mail.text().contains("Correlation ID: corr-1\n"));
        assertEquals(ID, mail.headers().get("X-Box1-Notification-Id"));
        assertEquals("ord_123", mail.headers().get("X-Box1-Order-Id"));
    }

    @Test
    void ordersChannelGoesToTheOrdersMailbox() {
        assertEquals("ops-orders@box1.local", OperatorMail.render(delivery("ORDER_REJECTED", "ops-orders", "x", "c"),
                "box1.local").recipient());
    }

    @Test
    void aHostileCorrelationIdCannotAddLinesOrHeaders() {
        OperatorMail.Rendered mail = OperatorMail.render(delivery("ORDER_SHIPPED", "ops-fulfillment", "x",
                "c\r\nBcc: someone@example.com"), "box1.local");
        assertFalse(mail.text().contains("\nBcc:"));
        assertFalse(mail.subject().contains("\n"));
    }

    @Test
    void invalidIdentifiersAreRefusedBeforeAnythingIsSent() {
        assertThrows(IllegalArgumentException.class, () -> OperatorMail.render(new Delivery(ID, "ord_1\r\nBcc: x@y.z",
                "ORDER_SHIPPED", "ops-fulfillment", "ORDER_SHIPPED@v1", "x", "c"), "box1.local"));
        assertThrows(IllegalArgumentException.class, () -> OperatorMail.render(new Delivery(ID, "ord_1", "ORDER_SHIPPED",
                "customer@example.com", "ORDER_SHIPPED@v1", "x", "c"), "box1.local"));
        assertThrows(IllegalArgumentException.class, () -> OperatorMail.render(new Delivery(ID, "ord_1", "NEW_TYPE",
                "ops-fulfillment", "NEW_TYPE@v1", "x", "c"), "box1.local"));
    }

    @Test
    void smtpRepliesAndSocketFailuresMapToTransientOrPermanent() throws Exception {
        InternetAddress[] none = new InternetAddress[0];
        ProviderResult busy = MailHogProviderAccess.classify(new SMTPSendFailedException("DATA", 451, "busy", null, none, none, none));
        assertEquals(503, busy.httpStatus());
        ProviderResult rejected = MailHogProviderAccess.classify(new SMTPSendFailedException("DATA", 554, "no", null, none, none, none));
        assertEquals(422, rejected.httpStatus());
        assertEquals(ProviderResult.Kind.TIMEOUT, MailHogProviderAccess.classify(
                new MessagingException("read", new SocketTimeoutException())).kind());
        assertEquals(ProviderResult.Kind.ERROR, MailHogProviderAccess.classify(
                new MessagingException("connect", new ConnectException())).kind());
    }
}
