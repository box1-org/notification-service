package org.box1.notification;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.box1.notification.domain.Fact;
import org.box1.notification.domain.InboundEvent;
import org.box1.notification.store.NotificationStore;

/**
 * Applies one consumed event: the dedupe record, the intent and its source are written in one transaction (decision
 * D-003). Delivery happens later, from the database, so consuming never waits for a provider.
 */
@ApplicationScoped
public class IntentService {
    private final NotificationStore store;
    private final Channels channels;

    public IntentService(NotificationStore store, Channels channels) {
        this.store = store;
        this.channels = channels;
    }

    /** Outcome of applying an event: created, merged (another event already reported the fact), duplicate or ignored. */
    public record Applied(String outcome, String notificationId) {}

    @Transactional
    public Applied apply(InboundEvent event) {
        Optional<Fact> fact = NoticeCatalog.factFor(event);
        if (!store.markProcessed(event.eventId(), event.eventType(), fact.isPresent() ? "notice" : "ignored")) {
            return new Applied("duplicate", null);
        }
        if (fact.isEmpty()) {
            return new Applied("ignored", null);
        }
        Fact f = fact.get();
        String notificationId = f.notificationId();
        boolean created = store.insertIntent(new NotificationStore.NewIntent(notificationId, f.dedupeKey(), f.orderId(),
                f.noticeType().name(), channels.forType(f.noticeType()), Templates.version(f.noticeType()),
                Templates.render(f.noticeType(), event), event.correlationId(), event.eventId(), event.traceparent()));
        store.addSource(notificationId, event.eventId(), event.eventType());
        return new Applied(created ? "created" : "merged", notificationId);
    }
}
