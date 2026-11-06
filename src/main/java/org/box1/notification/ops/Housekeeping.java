package org.box1.notification.ops;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.NotificationStore;
import org.box1.notification.store.StoreException;
import org.box1.notification.store.StubStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Ends expired stub faults on time, with an audit entry, and applies retention (decision D-010). */
@ApplicationScoped
public class Housekeeping {
    private static final Logger LOG = Logger.getLogger(Housekeeping.class);

    private final StubStore stub;
    private final NotificationStore store;
    private final long terminalDays;
    private final long processedDays;
    private final long outboxDays;

    public Housekeeping(StubStore stub, NotificationStore store,
            @ConfigProperty(name = "notification.retention.terminal-days") long terminalDays,
            @ConfigProperty(name = "notification.retention.processed-event-days") long processedDays,
            @ConfigProperty(name = "notification.retention.published-outbox-days") long outboxDays) {
        if (terminalDays < 1 || processedDays < 15 || outboxDays < 1) {
            throw new IllegalStateException("Retention must keep processed events longer than the 14-day topic retention");
        }
        this.stub = stub;
        this.store = store;
        this.terminalDays = terminalDays;
        this.processedDays = processedDays;
        this.outboxDays = outboxDays;
    }

    @Scheduled(every = "${notification.stub.sweep-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweepFaults() {
        try {
            if (stub.sweepExpired()) {
                LOG.info("Stub fault expired");
            }
        } catch (StoreException error) {
            LOG.warnv("Fault sweep failed: {0}", error.getMessage());
        }
    }

    @Scheduled(cron = "${notification.retention.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void retention() {
        try {
            int[] removed = store.purge(terminalDays, processedDays, outboxDays);
            LOG.infov("Retention removed notifications={0} processedEvents={1} outbox={2}", removed[0], removed[1],
                    removed[2]);
        } catch (StoreException error) {
            LOG.warnv("Retention failed: {0}", error.getMessage());
        }
    }
}
