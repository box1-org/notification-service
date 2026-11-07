package org.box1.notification.delivery;

/**
 * What a provider is asked to deliver. Every field is an internal operational identifier or text rendered from the
 * versioned templates; no customer data reaches a provider.
 */
public record Delivery(String notificationId, String orderId, String noticeType, String channel, String templateVersion,
        String body, String correlationId) {}
