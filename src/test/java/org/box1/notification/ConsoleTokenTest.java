package org.box1.notification;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/** With the console client accepted (the proposed D-007 option, pending approval): viewers read, nothing else. */
@QuarkusTest
@TestProfile(ConsoleTokenTest.ConsoleAccepted.class)
class ConsoleTokenTest {
    /** Accept tokens forwarded by the operator console backend. */
    public static class ConsoleAccepted implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("notification.auth.console-client-id", "operations-console",
                    "mp.jwt.verify.audiences", "notification-service,operations-console");
        }
    }

    @Test
    void consoleViewerAndOperatorCanRead() {
        for (String role : new String[] {"console-viewer", "console-operator"}) {
            given().auth().oauth2(TestTokens.console(role)).when().get("/api/v1/notifications?orderId=ord_1")
                    .then().statusCode(503).body("code", is("STORE_UNAVAILABLE"));
        }
    }

    @Test
    void consoleDeveloperCannotRead() {
        given().auth().oauth2(TestTokens.console("console-developer")).when().get("/api/v1/notifications?orderId=ord_1")
                .then().statusCode(403);
    }

    @Test
    void aConsoleTokenNeverGetsFaultControlEvenWithTheRoleOnThisClient() {
        given().auth().oauth2(TestTokens.console("console-operator")).contentType(ContentType.JSON)
                .body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60}").when().post("/api/v1/stub/faults")
                .then().statusCode(403).body("code", is("FORBIDDEN"));
    }

    @Test
    void otherClientsAreStillRefused() {
        given().auth().oauth2(TestTokens.otherClient()).when().get("/api/v1/notifications?orderId=ord_1")
                .then().statusCode(403);
    }
}
