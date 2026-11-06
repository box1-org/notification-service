package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Dead-letter replay as the runbook describes it: the replay channel is enabled, reads the notification dead-letter
 * topic with its own group and applies records here. Nothing is produced to topics other services consume.
 */
@QuarkusTest
@Tag("containers")
@QuarkusTestResource(value = FullStackResource.class, restrictToAnnotatedClass = true)
@TestProfile(ReplayTest.ReplayEnabled.class)
class ReplayTest {
    /** NOTIFY_DLQ_REPLAY_ENABLED=true. */
    public static class ReplayEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("mp.messaging.incoming.dlq-replay.enabled", "true");
        }
    }

    @Test
    void aMisroutedRecordIsDeadLetteredThenReplayedOnceAndGarbageStaysDeadLettered() {
        String key = Harness.orderId();
        long orderTopicBefore = Harness.read(Harness.FULFILLMENT_TOPIC, key, 1, Duration.ofSeconds(2)).size();
        Harness.send(Harness.ORDER_TOPIC, key, "{not json".getBytes(StandardCharsets.UTF_8));
        String misrouted = Events.shipmentConfirmed(key).toString();
        Harness.send(Harness.ORDER_TOPIC, key, misrouted);
        List<ConsumerRecord<String, byte[]>> dead = Harness.read(Harness.DLQ_TOPIC, key, 2, Duration.ofSeconds(30));
        assertEquals(2, dead.size(), "both reach the dead-letter topic first");
        List<Map<String, Object>> rows = Harness.terminal(key, 1);
        assertEquals(1, rows.size());
        assertEquals("ORDER_SHIPPED", rows.get(0).get("notice_type"));
        assertEquals("DELIVERED", rows.get(0).get("status"));
        // The garbage record stays in the dead-letter topic: replay skipped it rather than re-dead-lettering it.
        assertEquals(2, Harness.read(Harness.DLQ_TOPIC, key, 3, Duration.ofSeconds(5)).size());
        // Nothing was produced to the shared fulfillment topic.
        assertEquals(orderTopicBefore, Harness.read(Harness.FULFILLMENT_TOPIC, key, 1, Duration.ofSeconds(3)).size());
        String metrics = io.restassured.RestAssured.given().when().get("/q/metrics").asString();
        assertTrue(metrics.contains("type=\"replay\"") && metrics.contains("still_poison"), "still-poison counted");
    }
}
