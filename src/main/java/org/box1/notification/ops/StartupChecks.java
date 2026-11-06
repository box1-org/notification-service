package org.box1.notification.ops;

import java.time.Instant;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.box1.notification.api.StubResource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Fails startup on configuration that would break the delivery or offset policy. */
@ApplicationScoped
public class StartupChecks {
    private static final Logger LOG = Logger.getLogger(StartupChecks.class);

    void onStart(@Observes StartupEvent event,
            @ConfigProperty(name = "notification.consumer.events-floor") Instant floor,
            @ConfigProperty(name = "notification.provider") String provider,
            @ConfigProperty(name = "notification.stub.max-ttl-seconds") int maxTtlSeconds) {
        if (maxTtlSeconds < StubResource.MIN_TTL_SECONDS || maxTtlSeconds > StubResource.MAX_TTL_CAP_SECONDS) {
            throw new IllegalStateException("notification.stub.max-ttl-seconds must be between "
                    + StubResource.MIN_TTL_SECONDS + " and " + StubResource.MAX_TTL_CAP_SECONDS);
        }
        if (floor.isAfter(Instant.now())) {
            throw new IllegalStateException("NOTIFY_EVENTS_FLOOR is in the future");
        }
        if (!"stub".equals(provider)) {
            throw new IllegalStateException("Only the stub provider exists; a real provider needs approval and a secret");
        }
        LOG.infov("Events floor {0}; provider {1}", floor, provider);
    }

    /**
     * The dead-letter topic must exist before records are consumed; otherwise the broker may auto-create it with the
     * wrong partitions and retention, or a failing dead-letter send stalls the channel (messaging review 4). A reachable
     * broker without it stops startup. An unreachable broker does not: Kafka is deliberately not a startup dependency.
     */
    void checkDeadLetterTopic(@Observes StartupEvent event,
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrap,
            @ConfigProperty(name = "notification.dlq.topic") String topic,
            @ConfigProperty(name = "notification.startup.check-dlq-topic") boolean enabled) {
        if (!enabled) {
            return;
        }
        java.util.Properties config = new java.util.Properties();
        config.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(org.apache.kafka.clients.admin.AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(org.apache.kafka.clients.admin.AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        try (org.apache.kafka.clients.admin.Admin admin = org.apache.kafka.clients.admin.Admin.create(config)) {
            admin.describeTopics(java.util.List.of(topic)).allTopicNames().get(10, java.util.concurrent.TimeUnit.SECONDS);
            LOG.infov("Dead-letter topic {0} exists", topic);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException) {
                throw new IllegalStateException("Dead-letter topic " + topic + " does not exist; create it before deploying");
            }
            LOG.warnv("Could not check the dead-letter topic: {0}", error.getCause().getClass().getSimpleName());
        } catch (java.util.concurrent.TimeoutException error) {
            LOG.warn("Could not check the dead-letter topic: Kafka did not answer in time");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
