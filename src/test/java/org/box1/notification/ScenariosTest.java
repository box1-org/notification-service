package org.box1.notification;

import static io.restassured.RestAssured.given;
import static org.box1.notification.Harness.FULFILLMENT_TOPIC;
import static org.box1.notification.Harness.ORDER_TOPIC;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.box1.notification.delivery.Delivery;
import org.box1.notification.delivery.StubProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The prompt's verification scenarios against real PostgreSQL and Kafka. Each scenario uses its own order ID. The stub
 * fault is global, so every scenario waits for its own notices to reach a terminal state and clears the fault after.
 */
@QuarkusTest
@Tag("containers")
@QuarkusTestResource(value = FullStackResource.class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScenariosTest {
    private static final LogCapture LOGS = LogCapture.install();

    @Inject
    StubProvider stub;

    @AfterEach
    void clearFault() {
        io.restassured.response.Response response = given().auth().oauth2(TestTokens.faultOperator()).when()
                .delete("/api/v1/stub/faults");
        assertEquals(200, response.statusCode(), response.asString());
    }

    @Test
    @Order(16)
    void aFaultOperatorWithoutTheReaderRoleCanSetAndClear() {
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON)
                .body("{\"mode\":\"SLOW\",\"ttlSeconds\":30,\"delayMillis\":200}").when().post("/api/v1/stub/faults")
                .then().statusCode(200).body("activeFault.mode", is("SLOW"));
        given().auth().oauth2(TestTokens.faultOperator()).when().get("/api/v1/stub").then().statusCode(403);
    }

    private static void fault(String body) {
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/stub/faults").then().statusCode(200);
    }

    private static List<JsonNode> published(String orderId, int expected) {
        return Harness.read(Harness.EVENTS_TOPIC, orderId, expected, Duration.ofSeconds(15)).stream().map(r -> {
            try {
                return Events.JSON.readTree(r.value());
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }).toList();
    }

    private static Response api(String orderId) {
        return given().auth().oauth2(TestTokens.reader()).queryParam("orderId", orderId).when().get("/api/v1/notifications");
    }

    @Test
    @Order(1)
    void duplicateShippedEventGivesOneIntentAndOneDelivery() {
        String order = Harness.orderId();
        String event = Events.shipmentConfirmed(order).toString();
        Harness.send(FULFILLMENT_TOPIC, order, event);
        Harness.send(FULFILLMENT_TOPIC, order, event);
        Harness.await("duplicate processed", Duration.ofSeconds(20),
                () -> Harness.count("SELECT count(*) FROM processed_event pe JOIN notification_source s USING (event_id)"
                        + " WHERE s.notification_id IN (SELECT notification_id FROM notification WHERE order_id = ?)", order),
                n -> n == 1);
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals(1, rows.size());
        assertEquals("DELIVERED", rows.get(0).get("status"));
        assertEquals(1L, Harness.count("SELECT count(*) FROM delivery_receipt r JOIN notification n USING (notification_id)"
                + " WHERE n.order_id = ?", order));
        assertEquals(1, published(order, 2).size());
    }

    @Test
    @Order(2)
    void sameFactFromTwoEventsGivesOneIntentAndOneDelivery() {
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "FULFILLMENT_REQUESTED", "SHIPPED", "SHIPMENT_CONFIRMED")
                .toString());
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        Harness.await("both sources recorded", Duration.ofSeconds(20),
                () -> Harness.count("SELECT count(*) FROM notification_source s JOIN notification n USING (notification_id)"
                        + " WHERE n.order_id = ?", order), n -> n == 2);
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals(1, rows.size());
        assertEquals("ORDER_SHIPPED", rows.get(0).get("notice_type"));
        assertEquals(1L, Harness.count("SELECT count(*) FROM delivery_receipt r JOIN notification n USING (notification_id)"
                + " WHERE n.order_id = ?", order));
        assertEquals(1, published(order, 2).size());
        api(order).then().statusCode(200).body("count", is(1)).body("notifications[0].sources.size()", is(2));
    }

    @Test
    @Order(3)
    void nonRetryableFailureAndTheResultingExceptionAreOneNotice() {
        String order = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentFailed(order, false, "CARRIER_REJECTED").toString());
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "FULFILLMENT_REQUESTED", "EXCEPTION", "SHIPMENT_FAILED")
                .toString());
        Harness.await("both sources recorded", Duration.ofSeconds(20),
                () -> Harness.count("SELECT count(*) FROM notification_source s JOIN notification n USING (notification_id)"
                        + " WHERE n.order_id = ?", order), n -> n == 2);
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals(1, rows.size());
        assertEquals("SHIPMENT_FAILED", rows.get(0).get("notice_type"));
    }

    @Test
    @Order(4)
    void retryableShipmentFailureStillProducesAnOperatorNotice() {
        String order = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentFailed(order, true, "CARRIER_TIMEOUT").toString());
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals("SHIPMENT_DELAYED", rows.get(0).get("notice_type"));
        assertEquals("ops-fulfillment", rows.get(0).get("channel"));
        api(order).then().statusCode(200).body("notifications[0].status", is("DELIVERED"))
                .body("notifications[0].text", is("Shipment for order " + order + " is delayed: fulfillment request ful_"
                        + order.substring(4) + " failed with retryable reason CARRIER_TIMEOUT. The order state is unchanged"
                        + " and the fulfillment adapter will retry."))
                .body("notifications[0].receipt.receiptId", notNullValue());
    }

    @Test
    @Order(5)
    void stub503ShowsIncreasingAttemptsThenSuccess() {
        fault("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":120,\"failCount\":2}");
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "SUBMITTED", "REJECTED", "INSUFFICIENT_STOCK").toString());
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals("DELIVERED", rows.get(0).get("status"));
        assertEquals(3, rows.get(0).get("attempt_count"));
        List<Map<String, Object>> attempts = Harness.rows("SELECT attempt, outcome, http_status, started_at FROM delivery_attempt"
                + " WHERE notification_id = ? ORDER BY attempt", rows.get(0).get("notification_id"));
        assertEquals(List.of("TRANSIENT", "TRANSIENT", "SUCCESS"), attempts.stream().map(a -> a.get("outcome")).toList());
        assertEquals(503, attempts.get(0).get("http_status"));
        assertTrue(((java.sql.Timestamp) attempts.get(2).get("started_at"))
                .after((java.sql.Timestamp) attempts.get(0).get("started_at")));
        api(order).then().statusCode(200).body("notifications[0].attemptCount", is(3))
                .body("notifications[0].failure", nullValue())
                .body("notifications[0].attempts.attempt", is(List.of(1, 2, 3)));
    }

    @Test
    @Order(6)
    void permanentFourHundredIsNotRetried() {
        fault("{\"mode\":\"PERMANENT_4XX\",\"ttlSeconds\":60}");
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "SUBMITTED", "EXCEPTION", "RESERVATION_EXPIRED").toString());
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals("FAILED", rows.get(0).get("status"));
        assertEquals(1, rows.get(0).get("attempt_count"));
        assertEquals("PERMANENT_REJECTION", rows.get(0).get("failure_category"));
        api(order).then().statusCode(200).body("notifications[0].failure.category", is("PERMANENT_REJECTION"))
                .body("notifications[0].failure.code", is("PROVIDER_REJECTED"))
                .body("notifications[0].failure.httpStatus", is(422));
        JsonNode event = published(order, 1).get(0);
        assertEquals("notification.failed.v1", event.get("eventType").asText());
        assertEquals(422, event.get("payload").get("httpStatus").asInt());
    }

    @Test
    @Order(7)
    void retryExhaustionEndsFailedAndStopsRetrying() throws InterruptedException {
        fault("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":120}");
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "SUBMITTED", "REJECTED", "UNKNOWN_SKU").toString());
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals("FAILED", rows.get(0).get("status"));
        assertEquals("RETRIES_EXHAUSTED", rows.get(0).get("failure_category"));
        assertEquals(6, rows.get(0).get("attempt_count"));
        Thread.sleep(2500);
        assertEquals(6L, Harness.count("SELECT count(*) FROM delivery_attempt WHERE notification_id = ?",
                rows.get(0).get("notification_id")), "no attempt after the limit");
        api(order).then().statusCode(200).body("notifications[0].status", is("FAILED"))
                .body("notifications[0].failure.code", is("PROVIDER_UNAVAILABLE"))
                .body("notifications[0].failure.httpStatus", is(503));
        assertEquals("RETRIES_EXHAUSTED", published(order, 1).get(0).get("payload").get("failureCategory").asText());
    }

    @Test
    @Order(8)
    void poisonRecordsReachTheDeadLetterTopicUnchangedAndReplay() {
        String key = Harness.orderId();
        byte[] garbage = "{not json".getBytes(StandardCharsets.UTF_8);
        Harness.send(ORDER_TOPIC, key, garbage);
        // A valid shipment event published to the wrong topic is poison there; the runbook replay sends it to the right one.
        String misrouted = Events.shipmentConfirmed(key).toString();
        Harness.send(ORDER_TOPIC, key, misrouted);
        List<ConsumerRecord<String, byte[]>> dead = Harness.read(Harness.DLQ_TOPIC, key, 2, Duration.ofSeconds(30));
        assertEquals(2, dead.size());
        assertArrayEquals(garbage, dead.get(0).value());
        assertArrayEquals(misrouted.getBytes(StandardCharsets.UTF_8), dead.get(1).value());
        assertEquals(ORDER_TOPIC, Harness.header(dead.get(0), "dead-letter-topic"));
        assertEquals("Record value is not valid JSON", Harness.header(dead.get(0), "dead-letter-reason"));
        assertEquals("Unsupported eventType", Harness.header(dead.get(1), "dead-letter-reason"));
        assertTrue(Harness.notifications(key).isEmpty());
        // A well-formed event of a type this service does not know is acknowledged, not dead-lettered.
        String unknown = Events.stateChanged(key, "SUBMITTED", "RESERVED", "OK").put("eventType", "order.archived.v1").toString();
        Harness.send(ORDER_TOPIC, key, unknown);
        Harness.await("unknown type counted", Duration.ofSeconds(20), () -> given().when().get("/q/metrics").asString(),
                m -> m.contains("outcome=\"ignored_unknown_type\""));
        assertEquals(2, Harness.read(Harness.DLQ_TOPIC, key, 3, Duration.ofSeconds(5)).size());
    }

    @Test
    @Order(9)
    void eventsBeforeTheFloorProduceNoNotice() {
        String order = Harness.orderId();
        ObjectNode old = Events.shipmentConfirmed(order);
        old.put("occurredAt", Instant.parse(FullStackResource.FLOOR).minusSeconds(60).toString());
        Harness.send(FULFILLMENT_TOPIC, order, old.toString());
        String marker = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentFailed(marker, true, "AFTER_FLOOR").toString());
        Harness.terminal(marker, 1);
        assertTrue(Harness.notifications(order).isEmpty());
        assertEquals(0L, Harness.count("SELECT count(*) FROM processed_event WHERE event_id = ?",
                UUID.fromString(old.get("eventId").asText())));
    }

    @Test
    @Order(10)
    void brokerOutageLeavesABoundedOutboxBacklogThatPublishesLater() {
        String order = Harness.orderId();
        String id = FullStackResource.kafka.getContainerId();
        FullStackResource.kafka.getDockerClient().pauseContainerCmd(id).exec();
        try {
            // The intent is written directly, as the consumer would; delivery needs only the database.
            Harness.execute("INSERT INTO notification (notification_id, dedupe_key, order_id, notice_type, channel,"
                    + " template_version, body, status, next_attempt_at, correlation_id, causation_id) VALUES (?, ?, ?,"
                    + " 'ORDER_REJECTED', 'ops-orders', 'ORDER_REJECTED@v1', 'Order rejected.', 'PENDING', now(), 'c', ?)",
                    "ntf_" + "0".repeat(10) + order.substring(5, 21), "ORDER_REJECTED:" + order, order, UUID.randomUUID());
            Harness.terminal(order, 1);
            Harness.await("outbox backlog", Duration.ofSeconds(5),
                    () -> Harness.count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NULL", order),
                    n -> n == 1);
            given().when().get("/q/health/ready").then().statusCode(200);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        } finally {
            FullStackResource.kafka.getDockerClient().unpauseContainerCmd(id).exec();
        }
        Harness.await("outbox published after recovery", Duration.ofSeconds(60),
                () -> Harness.count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NOT NULL", order),
                n -> n == 1);
        assertEquals(1, published(order, 1).size());
        assertEquals(1L, Harness.count("SELECT count(*) FROM outbox WHERE aggregate_id = ?", order), "bounded: one row");
    }

    @Test
    @Order(11)
    void aWorkerThatDiesAfterTheProviderAcceptedCausesNoSecondDelivery() throws SQLException {
        String order = Harness.orderId();
        String id = "ntf_" + "1".repeat(10) + order.substring(5, 21);
        // A retry is pending (attempt 1 failed) and a worker that has since died holds the lease for attempt 2...
        Harness.execute("INSERT INTO notification (notification_id, dedupe_key, order_id, notice_type, channel,"
                + " template_version, body, status, attempt_count, next_attempt_at, claimed_by, claimed_until, correlation_id,"
                + " causation_id) VALUES (?, ?, ?, 'ORDER_REJECTED', 'ops-orders', 'ORDER_REJECTED@v1', 'Order rejected.',"
                + " 'RETRY_SCHEDULED', 1, now(), 'dead-worker', now() + interval '2 seconds', 'c', ?)",
                id, "ORDER_REJECTED:" + order, order, UUID.randomUUID());
        // ...after its provider call was accepted but before it recorded the result.
        String first = stub.deliver(new Delivery(id, order, "ORDER_REJECTED", "ops-orders", "ORDER_REJECTED@v1",
                "Order rejected.", "c")).receiptId();
        List<Map<String, Object>> rows = Harness.terminal(order, 1);
        assertEquals("DELIVERED", rows.get(0).get("status"));
        assertEquals(2, rows.get(0).get("attempt_count"));
        List<Map<String, Object>> receipts = Harness.rows("SELECT receipt_id FROM delivery_receipt WHERE notification_id = ?", id);
        assertEquals(1, receipts.size());
        assertEquals(first, receipts.get(0).get("receipt_id"));
        assertEquals(first, published(order, 1).get(0).get("payload").get("receiptId").asText());
    }

    @Test
    @Order(12)
    void faultBoundsAreEnforcedServerSideAndAudited() {
        String[] invalid = {"{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":5}", "{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":901}",
            "{\"mode\":\"TRANSIENT_503\"}", "{\"mode\":\"DROP_TABLES\",\"ttlSeconds\":60}",
            "{\"mode\":\"SLOW\",\"ttlSeconds\":60,\"delayMillis\":9000}", "{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60,\"failCount\":0}",
            "{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60,\"delayMillis\":100}"};
        for (String body : invalid) {
            given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON).body(body)
                    .when().post("/api/v1/stub/faults").then().statusCode(400).contentType("application/problem+json");
        }
        given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/stub").then().statusCode(200)
                .body("activeFault", nullValue()).body("faultControl.open", is(true))
                .body("faultControl.namespace", is(FullStackResource.NAMESPACE));
        List<Map<String, Object>> refused = Harness.rows("SELECT actor, outcome FROM stub_fault_audit WHERE outcome LIKE 'REFUSED_%'");
        assertTrue(refused.size() >= invalid.length);
        assertTrue(refused.stream().allMatch(r -> "op-faults".equals(r.get("actor"))));
        assertFalse(Harness.rows("SELECT 1 FROM stub_fault_audit WHERE outcome = 'REFUSED_INVALID_TTL'").isEmpty());
        assertTrue(Harness.rows("SELECT actor_subject FROM stub_fault_audit WHERE outcome LIKE 'REFUSED_%'").stream()
                .allMatch(r -> "op-faults".equals(r.get("actor_subject"))));
        // Refused before the handler (wrong role, no token): audited as well.
        given().auth().oauth2(TestTokens.reader()).contentType(ContentType.JSON)
                .body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60}").when().post("/api/v1/stub/faults").then().statusCode(403);
        given().when().delete("/api/v1/stub/faults").then().statusCode(401);
        assertEquals(1L, Harness.count("SELECT count(*) FROM stub_fault_audit WHERE outcome = 'DENIED_FORBIDDEN' AND actor = 'op-reader'"));
        assertEquals(1L, Harness.count("SELECT count(*) FROM stub_fault_audit WHERE outcome = 'DENIED_UNAUTHENTICATED' AND actor = 'anonymous'"));
    }

    @Test
    @Order(13)
    void aFaultExpiresOnItsTtlWithAnAuditEntry() {
        fault("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":10}");
        given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/stub").then().statusCode(200)
                .body("activeFault.mode", is("TRANSIENT_503")).body("activeFault.setBy", is("op-faults"));
        Harness.await("fault expiry", Duration.ofSeconds(20),
                () -> given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/stub").then().extract().path("activeFault"),
                fault -> fault == null);
        Harness.await("expiry audited", Duration.ofSeconds(5),
                () -> Harness.count("SELECT count(*) FROM stub_fault_audit WHERE action = 'EXPIRED'"), n -> n >= 1);
        // A delivery after expiry is unaffected.
        String order = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        assertEquals(1, Harness.terminal(order, 1).get(0).get("attempt_count"));
    }

    @Test
    @Order(14)
    void theAuditTrailCannotBeChanged() throws SQLException {
        // Row triggers fire only on existing rows, so make sure one exists.
        Harness.execute("INSERT INTO stub_fault_audit (actor, action, outcome) VALUES ('test', 'CLEAR', 'NO_ACTIVE_FAULT')");
        for (String sql : new String[] {"UPDATE stub_fault_audit SET actor = 'x'", "DELETE FROM stub_fault_audit",
            "TRUNCATE stub_fault_audit"}) {
            try {
                Harness.execute(sql);
                throw new AssertionError("allowed: " + sql);
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("stub_fault_audit"), expected.getMessage());
            }
        }
    }

    @Test
    @Order(15)
    void readerSeesTheNoticeAndATokenWithoutTheRoleIsRefused() {
        String order = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        Harness.terminal(order, 1);
        given().auth().oauth2(TestTokens.noRole()).queryParam("orderId", order).when().get("/api/v1/notifications")
                .then().statusCode(403);
        given().queryParam("orderId", order).when().get("/api/v1/notifications").then().statusCode(401);
        String id = api(order).then().statusCode(200).body("orderId", is(order))
                .body("notifications[0].noticeType", is("ORDER_SHIPPED"))
                .body("notifications[0].channel", is("ops-fulfillment"))
                .body("notifications[0].templateVersion", is("ORDER_SHIPPED@v1"))
                .body("notifications[0].correlationId", is("corr-" + order))
                .body("notifications[0].traceparent", notNullValue())
                .body("notifications[0].receipt.bodySha256", notNullValue())
                .extract().path("notifications[0].notificationId");
        given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/notifications/" + id).then().statusCode(200)
                .body("status", is("DELIVERED"));
        given().auth().oauth2(TestTokens.reader()).queryParam("orderId", "x' OR 1=1").when().get("/api/v1/notifications")
                .then().statusCode(400).body("code", is("INVALID_ORDER_ID"));
        String metrics = given().when().get("/q/metrics").then().statusCode(200).extract().asString();
        for (String name : new String[] {"box1_notification_pending", "box1_notification_oldest_pending_age_seconds",
            "box1_notification_delivery_attempts_total", "box1_notification_delivered_total",
            "box1_notification_failed_total{category=\"RETRIES_EXHAUSTED\"",
            "box1_notification_events_consumed_total", "box1_notification_outbox_pending",
            "box1_notification_metrics_snapshot_age_seconds"}) {
            assertTrue(metrics.contains(name), name + " missing; box1 lines: " + java.util.Arrays.stream(metrics.split("\n"))
                    .filter(l -> l.startsWith("box1_")).toList());
        }
    }

    @Test
    @Order(99)
    void noCustomerIdentifierAppearsAnywhere() {
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.orderPlaced(order).toString());
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentFailed(order, true, "CARRIER_TIMEOUT").toString());
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "FULFILLMENT_REQUESTED", "SHIPPED", "OK").toString());
        Harness.terminal(order, 2);
        Harness.await("placed event processed", Duration.ofSeconds(10),
                () -> Harness.count("SELECT count(*) FROM processed_event WHERE outcome = 'ignored'"), n -> n >= 1);
        String sentinel = Events.SENTINEL_CUSTOMER;
        for (String table : new String[] {"processed_event", "notification", "notification_source", "delivery_attempt",
            "delivery_receipt", "outbox", "stub_fault", "stub_fault_audit"}) {
            for (Map<String, Object> row : Harness.rows("SELECT row_to_json(t)::text AS j FROM " + table + " t")) {
                assertFalse(row.get("j").toString().contains(sentinel), table);
                assertFalse(row.get("j").toString().contains("customer"), table);
            }
        }
        for (ConsumerRecord<String, byte[]> record : Harness.read(Harness.EVENTS_TOPIC, order, 2, Duration.ofSeconds(15))) {
            assertFalse(new String(record.value(), StandardCharsets.UTF_8).contains(sentinel));
        }
        assertFalse(api(order).then().statusCode(200).extract().asString().contains(sentinel));
        List<String> lines = LOGS.lines();
        assertTrue(lines.stream().anyMatch(l -> l.contains(order)), "the capture saw this order's log lines");
        assertTrue(lines.stream().noneMatch(l -> l.contains(sentinel)), "a log line contains the customer identifier");
    }
}
