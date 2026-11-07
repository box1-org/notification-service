package org.box1.notification;

import static io.restassured.RestAssured.given;
import static org.box1.notification.Harness.FULFILLMENT_TOPIC;
import static org.box1.notification.Harness.ORDER_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Real SMTP delivery to a real MailHog, verified from both sides: the service's database, API and events, and
 * MailHog's own API. Faults go through {@code /api/v1/delivery-faults}.
 */
@QuarkusTest
@Tag("containers")
@QuarkusTestResource(value = MailHogStackResource.class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MailHogDeliveryTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @AfterEach
    void clearFault() {
        given().auth().oauth2(TestTokens.faultOperator()).when().delete("/api/v1/delivery-faults").then().statusCode(200);
    }

    /** MailHog messages carrying this notification ID header. */
    private static List<JsonNode> mailbox(String notificationId) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create(MailHogStackResource.api() + "/api/v2/messages?limit=250")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode item : Events.JSON.readTree(response.body()).get("items")) {
                JsonNode header = item.path("Content").path("Headers").path("X-Box1-Notification-Id");
                if (header.isArray() && notificationId.equals(header.get(0).asText())) {
                    out.add(item);
                }
            }
            return out;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static String header(JsonNode message, String name) {
        try {
            return MimeUtility.decodeText(message.path("Content").path("Headers").path(name).get(0).asText());
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String body(JsonNode message) {
        try {
            byte[] raw = message.path("Content").path("Body").asText().getBytes(StandardCharsets.ISO_8859_1);
            try (java.io.InputStream in = MimeUtility.decode(new java.io.ByteArrayInputStream(raw),
                    header(message, "Content-Transfer-Encoding"))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static JsonNode published(String orderId) {
        try {
            return Events.JSON.readTree(Harness.read(Harness.EVENTS_TOPIC, orderId, 1, Duration.ofSeconds(15)).get(0).value());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String only(String orderId) {
        List<Map<String, Object>> rows = Harness.terminal(orderId, 1);
        assertEquals(1, rows.size());
        return (String) rows.get(0).get("notification_id");
    }

    @Test
    @Order(1)
    void aShippedOrderBecomesOneRealMessageInTheFulfillmentMailbox() {
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.orderPlaced(order).toString());
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        String id = only(order);
        assertEquals("DELIVERED", Harness.notifications(order).get(0).get("status"));

        List<JsonNode> messages = mailbox(id);
        assertEquals(1, messages.size());
        JsonNode message = messages.get(0);
        assertEquals("box1-notifications@box1.local", header(message, "From"));
        assertEquals("ops-fulfillment@box1.local", header(message, "To"));
        assertEquals("[Box1] Order shipped — " + order, header(message, "Subject"));
        assertEquals("<" + id + "@box1.local>", header(message, "Message-ID"));
        String text = body(message);
        assertTrue(text.startsWith("Shipment confirmed for order " + order + "."), text);
        assertTrue(text.contains("Notification ID: " + id), text);
        assertFalse(message.toString().contains(Events.SENTINEL_CUSTOMER), "MailHog message contains a customer identifier");

        Map<String, Object> receipt = Harness.rows("SELECT provider, message_id, recipient FROM delivery_receipt"
                + " WHERE notification_id = ?", id).get(0);
        assertEquals("mailhog", receipt.get("provider"));
        assertEquals("<" + id + "@box1.local>", receipt.get("message_id"));
        assertEquals("ops-fulfillment@box1.local", receipt.get("recipient"));

        JsonNode event = published(order);
        assertEquals("notification.delivered.v1", event.get("eventType").asText());
        assertEquals("mailhog", event.get("payload").get("provider").asText());

        given().auth().oauth2(TestTokens.reader()).queryParam("orderId", order).when().get("/api/v1/notifications")
                .then().statusCode(200);
        String api = given().auth().oauth2(TestTokens.reader()).queryParam("orderId", order).when()
                .get("/api/v1/notifications").then().extract().asString();
        assertTrue(api.contains("\"provider\":\"mailhog\""), api);
        assertTrue(api.contains("\"recipient\":\"ops-fulfillment@box1.local\""), api);
    }

    @Test
    @Order(2)
    void rejectedOrdersGoToTheOrdersMailbox() {
        String order = Harness.orderId();
        Harness.send(ORDER_TOPIC, order, Events.stateChanged(order, "SUBMITTED", "REJECTED", "UNKNOWN_SKU").toString());
        List<JsonNode> messages = mailbox(only(order));
        assertEquals(1, messages.size());
        assertEquals("ops-orders@box1.local", header(messages.get(0), "To"));
        assertEquals("[Box1] Order rejected — " + order, header(messages.get(0), "Subject"));
    }

    @Test
    @Order(3)
    void aDuplicateSourceEventCreatesNoSecondMessage() {
        String order = Harness.orderId();
        com.fasterxml.jackson.databind.node.ObjectNode failed = Events.shipmentFailed(order, true, "CARRIER_TIMEOUT");
        String event = failed.toString();
        Harness.send(FULFILLMENT_TOPIC, order, event);
        Harness.send(FULFILLMENT_TOPIC, order, event);
        String id = only(order);
        // A second record after the duplicate shows the duplicate was consumed before the mailbox is counted.
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        Harness.await("later event consumed", Duration.ofSeconds(20), () -> Harness.notifications(order).size(), n -> n == 2);
        Harness.terminal(order, 2);
        assertEquals(1L, Harness.count("SELECT count(*) FROM processed_event WHERE event_id = ?::uuid",
                failed.get("eventId").asText()));
        List<JsonNode> messages = mailbox(id);
        assertEquals(1, messages.size());
        assertEquals("[Box1] Shipment delayed — " + order, header(messages.get(0), "Subject"));
        assertTrue(body(messages.get(0)).contains("CARRIER_TIMEOUT"));
    }

    @Test
    @Order(4)
    void transientFaultsRetryThenDeliverExactlyOneMessage() {
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON)
                .body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60,\"failCount\":2}").when().post("/api/v1/delivery-faults")
                .then().statusCode(200);
        given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/delivery-faults").then().statusCode(200)
                .body("provider", org.hamcrest.CoreMatchers.is("mailhog"));
        String order = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        String id = only(order);
        Map<String, Object> row = Harness.notifications(order).get(0);
        assertEquals("DELIVERED", row.get("status"));
        assertEquals(3, row.get("attempt_count"));
        assertEquals(List.of("TRANSIENT", "TRANSIENT", "SUCCESS"), Harness.rows(
                "SELECT outcome FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt", id).stream()
                .map(r -> r.get("outcome")).toList());
        assertEquals(1, mailbox(id).size());
    }

    @Test
    @Order(5)
    void aPermanentFaultFailsVisiblyAndSendsNothing() {
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON)
                .body("{\"mode\":\"PERMANENT_4XX\",\"ttlSeconds\":60,\"failCount\":1}").when().post("/api/v1/delivery-faults")
                .then().statusCode(200);
        String order = Harness.orderId();
        Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
        String id = only(order);
        Map<String, Object> row = Harness.notifications(order).get(0);
        assertEquals("FAILED", row.get("status"));
        assertEquals("PERMANENT_REJECTION", row.get("failure_category"));
        assertEquals(0, mailbox(id).size());
        assertEquals("notification.failed.v1", published(order).get("eventType").asText());
    }

    @Test
    @Order(6)
    void aHungMailHogIsRetriedAndDeliversOnceWhenItReturns() {
        MailHogStackResource.relay.blackhole(true);
        String order = Harness.orderId();
        try {
            Harness.send(FULFILLMENT_TOPIC, order, Events.shipmentConfirmed(order).toString());
            Harness.await("a failed SMTP attempt", Duration.ofSeconds(15), () -> Harness.count(
                    "SELECT count(*) FROM delivery_attempt a JOIN notification n USING (notification_id)"
                    + " WHERE n.order_id = ? AND a.outcome = 'TRANSIENT'", order), n -> n >= 1);
            assertEquals("PROVIDER_TIMEOUT", Harness.rows("SELECT a.failure_code FROM delivery_attempt a JOIN notification n"
                    + " USING (notification_id) WHERE n.order_id = ? ORDER BY attempt LIMIT 1", order).get(0).get("failure_code"));
        } finally {
            MailHogStackResource.relay.blackhole(false);
        }
        String id = only(order);
        Map<String, Object> row = Harness.notifications(order).get(0);
        assertEquals("DELIVERED", row.get("status"), row.toString());
        assertTrue((Integer) row.get("attempt_count") >= 2);
        assertEquals(1, mailbox(id).size());
    }

    @Test
    @Order(7)
    void theOldStubPathIsAnAliasForDeliveryFaults() {
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON)
                .body("{\"mode\":\"SLOW\",\"ttlSeconds\":30,\"delayMillis\":200}").when().post("/api/v1/stub/faults")
                .then().statusCode(200);
        given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/delivery-faults").then().statusCode(200)
                .body("activeFault.mode", org.hamcrest.CoreMatchers.is("SLOW"));
        given().auth().oauth2(TestTokens.faultOperator()).when().get("/api/v1/delivery-faults/audit").then().statusCode(200);
    }
}
