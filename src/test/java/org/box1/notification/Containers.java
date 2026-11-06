package org.box1.notification;

import java.util.UUID;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL and Kafka matching the deployed major versions. Names start with run16- (run isolation). */
final class Containers {
    static final String POSTGRES_IMAGE = "docker.io/library/postgres:15-alpine";
    static final String KAFKA_IMAGE = "docker.io/apache/kafka:4.2.0";

    private Containers() {}

    private static String name(String kind) {
        return "run16-" + kind + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("notification_service").withUsername("notification_service")
                .withCreateContainerCmdModifier(cmd -> cmd.withName(name("pg")));
    }

    @SuppressWarnings("resource")
    static KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(name("kafka")));
    }
}
