package org.box1.notification.delivery;

/**
 * A delivery backend. Implementations must treat {@code notificationId} as an idempotency key: a repeated call for an
 * accepted notification returns the same receipt and delivers nothing new. Only the stub exists; a real provider needs
 * a provisioned secret and the user's approval (decision D-009).
 */
public interface DeliveryProvider {
    String name();

    ProviderResult deliver(String notificationId, String channel, String templateVersion, String body);
}
