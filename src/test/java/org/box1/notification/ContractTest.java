package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.box1.notification.messaging.EventFactory;
import org.box1.notification.messaging.EventParser;
import org.box1.notification.store.NotificationStore;
import org.junit.jupiter.api.Test;

/**
 * Validates against box1-ops-contracts at the commit pinned in pom.xml. The contracts are fetched, never copied, so this
 * repository holds no local schema. A fetch failure fails the test rather than skipping it.
 */
class ContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static String fetch(String path) throws IOException, InterruptedException {
        String ref = System.getProperty("contracts.ref");
        URI uri = URI.create("https://raw.githubusercontent.com/box1-org/box1-ops-contracts/" + ref + "/" + path);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path);
            return response.body();
        }
    }

    private static JsonSchema schema(String type) throws IOException, InterruptedException {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return FACTORY.getSchema(fetch("events/v1/" + type + ".schema.json"), config);
    }

    @Test
    void parserAcceptsEveryConsumedFixture() throws Exception {
        EventParser parser = new EventParser(JSON);
        Set<String> all = new TreeSet<>(EventParser.ORDER_TOPIC_TYPES);
        all.addAll(EventParser.FULFILLMENT_TOPIC_TYPES);
        Set<String> seen = new TreeSet<>();
        for (JsonNode fixture : JSON.readTree(fetch("fixtures/events.json"))) {
            String type = fixture.get("eventType").asText();
            if (all.contains(type)) {
                assertEquals(type, parser.parse(fixture.toString(), all).eventType());
                seen.add(type);
            }
        }
        assertEquals(all, seen);
    }

    @Test
    void producedEventsValidateAgainstTheEnvelopeAndPayloadSchemas() throws Exception {
        EventFactory factory = new EventFactory(JSON);
        NotificationStore.Claimed intent = new NotificationStore.Claimed("ntf_0123456789abcdef0123456789", "ord_1042",
                "SHIPMENT_FAILED", "ops-fulfillment", "SHIPMENT_FAILED@v1", "text", 0, "corr-1042", UUID.randomUUID(), null);
        JsonSchema envelope = schema("envelope");
        EventFactory.Built delivered = factory.delivered(intent, 1, "stub", "rcpt_0123456789abcdef01234567", Instant.now());
        EventFactory.Built failedHttp = factory.failed(intent, 6, "stub", "RETRIES_EXHAUSTED", "PROVIDER_UNAVAILABLE", 503,
                Instant.now());
        EventFactory.Built failedTimeout = factory.failed(intent, 6, "stub", "RETRIES_EXHAUSTED", "PROVIDER_TIMEOUT", null,
                Instant.now());
        EventFactory.Built deliveredMailHog = factory.delivered(intent, 1, "mailhog", "rcpt_0123456789abcdef01234567",
                Instant.now());
        EventFactory.Built rejectedMailHog = factory.failed(intent, 1, "mailhog", "PERMANENT_REJECTION", "PROVIDER_REJECTED",
                422, Instant.now());
        for (EventFactory.Built built : new EventFactory.Built[] {delivered, failedHttp, failedTimeout, deliveredMailHog,
            rejectedMailHog}) {
            JsonNode event = JSON.readTree(built.json());
            Set<ValidationMessage> errors = envelope.validate(event);
            assertTrue(errors.isEmpty(), built.eventType() + " envelope: " + errors);
            String name = built.eventType().replaceAll("\\.v[0-9]+$", "");
            errors = schema(name).validate(event.get("payload"));
            assertTrue(errors.isEmpty(), built.eventType() + " payload: " + errors);
            assertEquals(event.get("aggregateId"), event.get("payload").get("orderId"));
        }
    }
}
