package org.box1.notification.ops;

import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.NotificationStore;
import org.box1.notification.store.StoreException;

/** Business metrics (decision D-011). Gauges read a cached snapshot, so a scrape never queries the database. */
@ApplicationScoped
public class NotificationMetrics {
    private final MeterRegistry registry;
    private final NotificationStore store;
    private final AtomicReference<NotificationStore.Snapshot> snapshot =
            new AtomicReference<>(new NotificationStore.Snapshot(0, 0, 0, 0));

    public NotificationMetrics(MeterRegistry registry, NotificationStore store) {
        this.registry = registry;
        this.store = store;
        Gauge.builder("box1.notification.pending", snapshot, s -> s.get().pending())
                .description("Notifications not yet delivered or failed").register(registry);
        Gauge.builder("box1.notification.oldest.pending.age.seconds", snapshot, s -> s.get().oldestPendingAgeSeconds())
                .description("Age of the oldest notification not yet delivered or failed").register(registry);
        Gauge.builder("box1.outbox.pending", snapshot, s -> s.get().outboxPending())
                .description("Notification events not yet confirmed by the broker").register(registry);
        Gauge.builder("box1.outbox.oldest.pending.age.seconds", snapshot, s -> s.get().outboxOldestAgeSeconds())
                .description("Age of the oldest unpublished notification event").register(registry);
    }

    @Scheduled(every = "${notification.metrics.refresh-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        try {
            snapshot.set(store.snapshot());
        } catch (StoreException error) {
            // Keep the last snapshot; readiness reports the database.
        }
    }

    public void consumed(String type, String outcome) {
        Counter.builder("box1.notification.events.consumed").tag("type", type).tag("outcome", outcome).register(registry)
                .increment();
    }

    public void attempt(String outcome) {
        Counter.builder("box1.notification.delivery.attempts").tag("outcome", outcome).register(registry).increment();
    }

    public void delivered() {
        Counter.builder("box1.notification.delivered").register(registry).increment();
    }

    public void failed(String category) {
        Counter.builder("box1.notification.failed").tag("category", category).register(registry).increment();
    }

    public void consumerRetry() {
        Counter.builder("box1.notification.consumer.retries").register(registry).increment();
    }

    public void published(double lagSeconds) {
        Counter.builder("box1.outbox.published").register(registry).increment();
        DistributionSummary.builder("box1.outbox.publish.lag.seconds").register(registry).record(lagSeconds);
    }

    public void publishFailed() {
        Counter.builder("box1.outbox.publish.failures").register(registry).increment();
    }

    public void faultControl(String action, String outcome) {
        Counter.builder("box1.notification.stub.fault.control").tag("action", action).tag("outcome", outcome)
                .register(registry).increment();
    }
}
