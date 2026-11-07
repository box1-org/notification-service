package org.box1.notification.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.box1.notification.domain.FailureCategory;
import org.box1.notification.domain.FailureCode;
import org.box1.notification.messaging.EventFactory;
import org.box1.notification.ops.NotificationMetrics;
import org.box1.notification.store.NotificationStore;
import org.box1.notification.store.OutboxStore;
import org.box1.notification.store.StoreException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Delivers due notifications from the database (decision D-004). The claim, the provider call and the result are separate
 * steps, so no transaction spans the call. A crash after the provider accepted leaves a lease that expires; the retry
 * finds the receipt at the provider boundary, so delivery is effective once. One window remains: a crash after MailHog
 * accepted a message but before its receipt committed sends the message again on the next attempt.
 */
// Created at startup so invalid limits stop the application instead of failing on every poll.
@Startup
@ApplicationScoped
public class DeliveryWorker {
    /** Compiled hard caps; configuration can only lower them. */
    public static final int MAX_ATTEMPTS_CAP = 10;
    public static final double MAX_BACKOFF_CAP_SECONDS = 300;

    private static final Logger LOG = Logger.getLogger(DeliveryWorker.class);
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final NotificationStore store;
    private final OutboxStore outbox;
    private final EventFactory events;
    private final ProviderBoundary provider;
    private final NotificationMetrics metrics;
    private final Tracer tracer;
    private final int maxAttempts;
    private final double baseBackoffSeconds;
    private final double maxBackoffSeconds;
    private final long timeoutMillis;
    private final double leaseSeconds;
    private final int batchSize;
    private final String owner = "worker-" + UUID.randomUUID();
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean stopping = new AtomicBoolean();

    public DeliveryWorker(NotificationStore store, OutboxStore outbox, EventFactory events, ProviderBoundary provider,
            NotificationMetrics metrics, Tracer tracer,
            @ConfigProperty(name = "notification.delivery.max-attempts") int maxAttempts,
            @ConfigProperty(name = "notification.delivery.base-backoff-seconds") double baseBackoffSeconds,
            @ConfigProperty(name = "notification.delivery.max-backoff-seconds") double maxBackoffSeconds,
            @ConfigProperty(name = "notification.delivery.provider-timeout-millis") long timeoutMillis,
            @ConfigProperty(name = "notification.delivery.lease-seconds") double leaseSeconds,
            @ConfigProperty(name = "notification.delivery.batch-size") int batchSize) {
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_CAP) {
            throw new IllegalStateException("notification.delivery.max-attempts must be between 1 and " + MAX_ATTEMPTS_CAP);
        }
        if (maxBackoffSeconds <= 0 || maxBackoffSeconds > MAX_BACKOFF_CAP_SECONDS || baseBackoffSeconds <= 0
                || baseBackoffSeconds > maxBackoffSeconds) {
            throw new IllegalStateException("Delivery backoff must be positive and at most " + MAX_BACKOFF_CAP_SECONDS + " s");
        }
        if (timeoutMillis < 100 || timeoutMillis * 2 > leaseSeconds * 1000) {
            throw new IllegalStateException("The provider timeout must be at least 100 ms and under half the lease");
        }
        if (batchSize < 1 || (long) batchSize * timeoutMillis >= leaseSeconds * 1000) {
            // Calls in a batch run one after another; the last one must still start inside its lease.
            throw new IllegalStateException("notification.delivery.batch-size times the provider timeout must be under the lease");
        }
        this.store = store;
        this.outbox = outbox;
        this.events = events;
        this.provider = provider;
        this.metrics = metrics;
        this.tracer = tracer;
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.timeoutMillis = timeoutMillis;
        this.leaseSeconds = leaseSeconds;
        this.batchSize = batchSize;
    }

    void onStop(@Observes ShutdownEvent event) {
        // Stop claiming. An in-flight call finishes or its lease expires; the next start resumes from the database.
        stopping.set(true);
        calls.shutdown();
    }

    @Scheduled(every = "${notification.delivery.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        try {
            deliverDue();
        } catch (RuntimeException error) {
            LOG.warnv("Delivery poll failed: {0}", error.getClass().getSimpleName());
        }
    }

    /** Deliver one bounded batch. Returns the number of attempts recorded. */
    public int deliverDue() {
        if (stopping.get()) {
            return 0;
        }
        List<NotificationStore.Claimed> claimed = store.claimDue(owner, batchSize, leaseSeconds);
        int recorded = 0;
        for (NotificationStore.Claimed intent : claimed) {
            if (stopping.get()) {
                store.releaseClaim(intent.notificationId(), owner);
                continue;
            }
            try {
                recorded += deliver(intent) ? 1 : 0;
            } catch (RuntimeException error) {
                // One failure must not hold the rest of the batch until their leases expire.
                LOG.warnv("Delivery attempt not recorded; releasing the claim: error={0}", error.getClass().getSimpleName());
                releaseQuietly(intent);
            }
        }
        return recorded;
    }

    private boolean deliver(NotificationStore.Claimed intent) {
        Span span = tracer.spanBuilder("notification.deliver").setParent(extract(intent.traceparent()))
                .setAttribute("box1.order_id", intent.orderId()).setAttribute("box1.notification_id", intent.notificationId())
                .startSpan();
        MDC.put("orderId", intent.orderId());
        MDC.put("notificationId", intent.notificationId());
        MDC.put("correlationId", intent.correlationId());
        try (Scope ignored = span.makeCurrent()) {
            int attempt = intent.attemptCount() + 1;
            Instant started = Instant.now();
            ProviderResult result = call(intent);
            return record(intent, attempt, started, result);
        } finally {
            span.end();
            MDC.remove("orderId");
            MDC.remove("notificationId");
            MDC.remove("correlationId");
        }
    }

    private void releaseQuietly(NotificationStore.Claimed intent) {
        try {
            store.releaseClaim(intent.notificationId(), owner);
        } catch (StoreException error) {
            LOG.warn("Unable to release a claim; its lease will expire");
        }
    }

    private ProviderResult call(NotificationStore.Claimed intent) {
        Future<ProviderResult> future = calls.submit(() -> provider.deliver(new Delivery(intent.notificationId(),
                intent.orderId(), intent.noticeType(), intent.channel(), intent.templateVersion(), intent.body(),
                intent.correlationId())));
        // RejectedExecutionException during shutdown propagates to deliverDue, which releases the claim.
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            // The call may still complete. If it does, the next attempt gets its receipt and nothing is sent twice.
            return ProviderResult.timeout();
        } catch (ExecutionException error) {
            return ProviderResult.error();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ProviderResult.error();
        }
    }

    @Transactional
    boolean record(NotificationStore.Claimed intent, int attempt, Instant started, ProviderResult reported) {
        ProviderResult result = reported;
        if (Classifier.classify(reported) != Classifier.Outcome.SUCCESS) {
            // A call that timed out may have been accepted since; the row lock orders this against its receipt write.
            Optional<String> late = store.lockAndFindReceipt(intent.notificationId());
            if (late.isPresent()) {
                result = ProviderResult.accepted(late.get());
            }
        }
        Classifier.Outcome outcome = Classifier.classify(result);
        FailureCode code = Classifier.code(result);
        String status;
        String category = null;
        double delay = 0;
        switch (outcome) {
            case SUCCESS -> status = "DELIVERED";
            case PERMANENT -> {
                status = "FAILED";
                category = FailureCategory.PERMANENT_REJECTION.name();
            }
            default -> {
                if (attempt >= maxAttempts) {
                    status = "FAILED";
                    category = FailureCategory.RETRIES_EXHAUSTED.name();
                } else {
                    status = "RETRY_SCHEDULED";
                    delay = backoff(attempt);
                }
            }
        }
        NotificationStore.AttemptRecord attemptRecord = new NotificationStore.AttemptRecord(attempt, started,
                outcome.name(), code == null ? null : code.name(), result.httpStatus());
        Optional<Instant> at;
        try {
            at = store.recordAttempt(intent, owner, attemptRecord, status, delay, category);
        } catch (StoreException error) {
            LOG.warnv("Unable to record a delivery attempt; the lease will expire: error={0}", error.getMessage());
            throw error;
        }
        if (at.isEmpty()) {
            LOG.warn("Delivery result discarded: the lease was lost to another worker");
            return false;
        }
        metrics.attempt(outcome.name().toLowerCase(java.util.Locale.ROOT));
        if ("DELIVERED".equals(status)) {
            EventFactory.Built event = events.delivered(intent, attempt, provider.name(), result.receiptId(), at.get());
            outbox.enqueue(event.eventId(), intent.orderId(), intent.notificationId(), event.eventType(), event.json());
            metrics.delivered();
            LOG.infov("Notification delivered: attempt={0}", attempt);
        } else if ("FAILED".equals(status)) {
            EventFactory.Built event = events.failed(intent, attempt, provider.name(), category, code.name(),
                    result.httpStatus(), at.get());
            outbox.enqueue(event.eventId(), intent.orderId(), intent.notificationId(), event.eventType(), event.json());
            metrics.failed(category);
            LOG.warnv("Notification failed: attempt={0} category={1} code={2}", attempt, category, code);
        } else {
            LOG.infov("Notification retry scheduled: attempt={0} code={1} delaySeconds={2}", attempt, code, delay);
        }
        return true;
    }

    /** Delay before attempt n+1: base × 2^(n−1), capped. */
    public double backoff(int attempt) {
        double exponent = Math.min(Math.max(attempt - 1, 0), 30);
        return Math.min(baseBackoffSeconds * Math.pow(2, exponent), maxBackoffSeconds);
    }

    private static Context extract(String traceparent) {
        if (traceparent == null) {
            return Context.current();
        }
        Context context = W3CTraceContextPropagator.getInstance().extract(Context.current(),
                Map.of("traceparent", traceparent), GETTER);
        SpanContext parent = Span.fromContext(context).getSpanContext();
        return parent.isValid() ? context : Context.current();
    }
}
