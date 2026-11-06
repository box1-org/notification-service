package org.box1.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Starts PostgreSQL and Kafka, creates the topics as the cluster has them (3 partitions), and switches the service to
 * production behavior with short timings. Fault control is open for the namespace {@code run16-demo} only.
 */
public class FullStackResource implements QuarkusTestResourceLifecycleManager {
    static final String FLOOR = "2026-01-01T00:00:00Z";
    static final String NAMESPACE = "run16-demo";
    static PostgreSQLContainer<?> postgres;
    static KafkaContainer kafka;

    @Override
    public Map<String, String> start() {
        postgres = Containers.postgres();
        kafka = Containers.kafka();
        postgres.start();
        kafka.start();
        Properties admin = new Properties();
        admin.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (Admin client = Admin.create(admin)) {
            client.createTopics(List.of(new NewTopic("box1.order.events.v1", 3, (short) 1),
                    new NewTopic("box1.fulfillment.events.v1", 3, (short) 1),
                    new NewTopic("box1.notification.events.v1", 3, (short) 1),
                    new NewTopic("box1.notification.dlq.v1", 3, (short) 1))).all().get(60, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create test topics", error);
        }
        Map<String, String> config = new HashMap<>();
        config.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
        config.put("quarkus.datasource.username", postgres.getUsername());
        config.put("quarkus.datasource.password", postgres.getPassword());
        config.put("notification.readiness.database", "true");
        config.put("quarkus.flyway.migrate-at-start", "true");
        config.put("quarkus.scheduler.enabled", "true");
        config.put("kafka.bootstrap.servers", kafka.getBootstrapServers());
        config.put("mp.messaging.incoming.order-events.enabled", "true");
        config.put("mp.messaging.incoming.fulfillment-events.enabled", "true");
        config.put("notification.consumer.events-floor", FLOOR);
        config.put("notification.consumer.initial-backoff-millis", "20");
        config.put("notification.consumer.max-attempts", "5");
        config.put("notification.delivery.base-backoff-seconds", "0.2");
        config.put("notification.delivery.max-backoff-seconds", "1");
        config.put("notification.delivery.provider-timeout-millis", "500");
        config.put("notification.delivery.lease-seconds", "3");
        config.put("notification.delivery.poll-interval", "100ms");
        config.put("notification.outbox.poll-interval", "200ms");
        config.put("notification.outbox.lease-seconds", "3");
        config.put("notification.outbox.backoff-base-seconds", "0.2");
        config.put("notification.outbox.backoff-max-seconds", "1");
        config.put("notification.metrics.refresh-interval", "1s");
        config.put("notification.stub.faults-enabled", "true");
        config.put("notification.pod-namespace", NAMESPACE);
        config.put("notification.stub.fault-namespaces", NAMESPACE);
        config.put("notification.stub.sweep-interval", "1s");
        return config;
    }

    @Override
    public void stop() {
        if (kafka != null) {
            kafka.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }
}
