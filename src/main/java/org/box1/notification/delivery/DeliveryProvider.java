package org.box1.notification.delivery;

/**
 * A delivery backend behind {@link ProviderBoundary}. The boundary returns an existing receipt before calling a
 * provider, so a provider is only called for a notification that has none. A provider writes its receipt only after
 * its backend accepted the message, and only while the notification is not terminal.
 */
public interface DeliveryProvider {
    String name();

    ProviderResult deliver(Delivery delivery);
}
