package org.box1.notification;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.domain.NoticeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Named operator channels per notice type (decision D-002). No API or event can choose a recipient. */
@ApplicationScoped
public class Channels {
    private static final Pattern CHANNEL = Pattern.compile("^ops-[a-z0-9-]{1,40}$");
    private final Map<NoticeType, String> channels = new EnumMap<>(NoticeType.class);

    public Channels(@ConfigProperty(name = "notification.channel.order-shipped") String shipped,
            @ConfigProperty(name = "notification.channel.shipment-delayed") String delayed,
            @ConfigProperty(name = "notification.channel.shipment-failed") String failed,
            @ConfigProperty(name = "notification.channel.order-exception") String exception,
            @ConfigProperty(name = "notification.channel.order-rejected") String rejected) {
        channels.put(NoticeType.ORDER_SHIPPED, checked(shipped));
        channels.put(NoticeType.SHIPMENT_DELAYED, checked(delayed));
        channels.put(NoticeType.SHIPMENT_FAILED, checked(failed));
        channels.put(NoticeType.ORDER_EXCEPTION, checked(exception));
        channels.put(NoticeType.ORDER_REJECTED, checked(rejected));
    }

    private static String checked(String channel) {
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw new IllegalStateException("Operator channel names must match ops-<name>");
        }
        return channel;
    }

    public String forType(NoticeType type) {
        return channels.get(type);
    }
}
