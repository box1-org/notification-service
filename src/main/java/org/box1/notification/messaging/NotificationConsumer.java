package org.box1.notification.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.box1.notification.IntentService;
import org.box1.notification.domain.InboundEvent;
import org.box1.notification.domain.PoisonEventException;
import org.box1.notification.ops.NotificationMetrics;
import org.box1.notification.store.StoreException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Consumes order and fulfillment events (decision D-006). A thrown exception nacks the record and the connector's
 * dead-letter strategy moves it, unchanged, to the notification dead-letter topic. Offsets commit after processing.
 */
@ApplicationScoped
public class NotificationConsumer {
    private static final Logger LOG = Logger.getLogger(NotificationConsumer.class);
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

    private final EventParser parser;
    private final IntentService intents;
    private final NotificationMetrics metrics;
    private final Tracer tracer;
    private final Instant floor;
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;

    public NotificationConsumer(EventParser parser, IntentService intents, NotificationMetrics metrics, Tracer tracer,
            @ConfigProperty(name = "notification.consumer.events-floor") Instant floor,
            @ConfigProperty(name = "notification.consumer.max-attempts") int maxAttempts,
            @ConfigProperty(name = "notification.consumer.initial-backoff-millis") long initialBackoffMillis,
            @ConfigProperty(name = "notification.consumer.max-backoff-millis") long maxBackoffMillis) {
        this.parser = parser;
        this.intents = intents;
        this.metrics = metrics;
        this.tracer = tracer;
        this.floor = floor;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
    }

    @Incoming("order-events")
    @Blocking
    void onOrderEvent(ConsumerRecord<String, byte[]> record) {
        process(record, EventParser.ORDER_TOPIC_TYPES);
    }

    @Incoming("fulfillment-events")
    @Blocking
    void onFulfillmentEvent(ConsumerRecord<String, byte[]> record) {
        process(record, EventParser.FULFILLMENT_TOPIC_TYPES);
    }

    /** Bytes stay raw on the channel, so a dead-lettered record is exactly what arrived. Invalid UTF-8 is poison. */
    private static String text(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(record.value())).toString();
        } catch (CharacterCodingException error) {
            throw new PoisonEventException("Record value is not valid UTF-8");
        }
    }

    void process(ConsumerRecord<String, byte[]> record, Set<String> allowedTypes) {
        InboundEvent event;
        try {
            event = parser.parse(text(record), allowedTypes);
        } catch (PoisonEventException error) {
            metrics.consumed("unparsed", "dead_lettered");
            LOG.warnv("Dead-lettering malformed record: topic={0} partition={1} offset={2} reason={3}", record.topic(),
                    record.partition(), record.offset(), error.getMessage());
            throw error;
        }
        Span span = tracer.spanBuilder("notification.consume " + event.eventType()).setParent(extract(event.traceparent()))
                .setAttribute("box1.order_id", event.orderId()).startSpan();
        MDC.put("orderId", event.orderId());
        MDC.put("eventId", event.eventId().toString());
        MDC.put("eventType", event.eventType());
        MDC.put("correlationId", event.correlationId());
        try (Scope ignored = span.makeCurrent()) {
            if (event.occurredAt().isBefore(floor)) {
                // Acknowledged with no notice and no dedupe record: the first-start offset policy (decision D-006).
                metrics.consumed(event.eventType(), "skipped_before_floor");
                LOG.info("Skipping an event older than the configured floor");
                return;
            }
            try {
                IntentService.Applied applied = withRetries(event);
                metrics.consumed(event.eventType(), applied.outcome());
                if (applied.notificationId() != null) {
                    MDC.put("notificationId", applied.notificationId());
                }
                LOG.infov("Event {0}", applied.outcome());
            } catch (RuntimeException error) {
                span.recordException(error);
                metrics.consumed(event.eventType(), "dead_lettered");
                LOG.warnv("Dead-lettering event: topic={0} partition={1} offset={2} error={3}", record.topic(),
                        record.partition(), record.offset(), error.getClass().getSimpleName());
                if (error instanceof PoisonEventException) {
                    throw error;
                }
                // Fixed text: this message reaches dead-letter headers.
                throw new PoisonEventException("Retries exhausted");
            }
        } finally {
            span.end();
            MDC.remove("orderId");
            MDC.remove("eventId");
            MDC.remove("eventType");
            MDC.remove("correlationId");
            MDC.remove("notificationId");
        }
    }

    /**
     * A database that cannot serve holds the channel without using attempts: no event is at fault. Other failures are
     * bounded, then dead-lettered. Values that can never be stored are poison at once.
     */
    private IntentService.Applied withRetries(InboundEvent event) {
        long backoff = initialBackoffMillis;
        int attempt = 1;
        while (true) {
            try {
                return intents.apply(event);
            } catch (PoisonEventException error) {
                throw error;
            } catch (StoreException error) {
                if (error.isPermanent()) {
                    throw new PoisonEventException("Value cannot be stored");
                }
                if (!error.isUnavailable()) {
                    if (attempt >= maxAttempts) {
                        throw error;
                    }
                    attempt++;
                }
                backoff = pause(backoff, attempt, error);
            } catch (RuntimeException error) {
                if (attempt >= maxAttempts) {
                    throw error;
                }
                attempt++;
                backoff = pause(backoff, attempt, error);
            }
        }
    }

    private long pause(long backoff, int attempt, RuntimeException error) {
        metrics.consumerRetry();
        LOG.warnv("Retrying event: attempt={0} error={1}", attempt, error.getClass().getSimpleName());
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during event retry", interrupted);
        }
        return Math.min(backoff * 2, maxBackoffMillis);
    }

    private static Context extract(String traceparent) {
        if (traceparent == null) {
            return Context.current();
        }
        return W3CTraceContextPropagator.getInstance().extract(Context.current(), Map.of("traceparent", traceparent), GETTER);
    }
}
