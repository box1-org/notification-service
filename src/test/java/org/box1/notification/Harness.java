package org.box1.notification;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Test helpers over the containers started by {@link FullStackResource}. */
final class Harness {
    static final String ORDER_TOPIC = "box1.order.events.v1";
    static final String FULFILLMENT_TOPIC = "box1.fulfillment.events.v1";
    static final String EVENTS_TOPIC = "box1.notification.events.v1";
    static final String DLQ_TOPIC = "box1.notification.dlq.v1";

    private Harness() {}

    static String orderId() {
        return "ord_t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static void send(String topic, String key, String value) {
        send(topic, key, value.getBytes(StandardCharsets.UTF_8));
    }

    static void send(String topic, String key, byte[] value) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, FullStackResource.kafka.getBootstrapServers());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config, new StringSerializer(),
                new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception error) {
            throw new IllegalStateException("Test send failed", error);
        }
    }

    /** Every record on the topic with this key, read from the beginning until {@code expected} arrive or the time ends. */
    static List<ConsumerRecord<String, byte[]>> read(String topic, String key, int expected, Duration timeout) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FullStackResource.kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "run16-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline && found.size() < expected) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(FullStackResource.postgres.getJdbcUrl(), FullStackResource.postgres.getUsername(),
                FullStackResource.postgres.getPassword());
    }

    static List<Map<String, Object>> rows(String sql, Object... params) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                int columns = r.getMetaData().getColumnCount();
                while (r.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                    }
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    static long count(String sql, Object... params) {
        return ((Number) rows(sql, params).get(0).values().iterator().next()).longValue();
    }

    static void execute(String sql, Object... params) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            s.execute();
        }
    }

    static <T> T await(String what, Duration timeout, Supplier<T> supplier, Predicate<T> done) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = supplier.get();
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("Timed out waiting for " + what + "; last value: " + last);
        return last;
    }

    static List<Map<String, Object>> notifications(String orderId) {
        return rows("SELECT * FROM notification WHERE order_id = ? ORDER BY created_at", orderId);
    }

    static List<Map<String, Object>> terminal(String orderId, int expected) {
        return await("terminal notifications for " + orderId, Duration.ofSeconds(30), () -> notifications(orderId),
                list -> list.size() >= expected && list.stream()
                        .allMatch(n -> "DELIVERED".equals(n.get("status")) || "FAILED".equals(n.get("status"))));
    }
}
