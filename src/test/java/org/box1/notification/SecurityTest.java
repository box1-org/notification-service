package org.box1.notification;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Access rules without a database. A request that passes authorization reaches the store and gets 503, so 503 here
 * means "allowed", and 401/403 mean refused before any data access.
 */
@QuarkusTest
@QuarkusTestResource(value = TestTokens.class, restrictToAnnotatedClass = false)
class SecurityTest {
    private static final String LIST = "/api/v1/notifications?orderId=ord_1";
    private static final String[] READ_PATHS = {LIST, "/api/v1/notifications/ntf_0123456789abcdef0123456789", "/api/v1/stub",
        "/api/v1/stub/faults/audit"};

    @Test
    void anonymousRequestsGet401ProblemJson() {
        for (String path : READ_PATHS) {
            given().when().get(path).then().statusCode(401).contentType("application/problem+json")
                    .header("WWW-Authenticate", "Bearer");
        }
        given().contentType(ContentType.JSON).body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60}")
                .when().post("/api/v1/stub/faults").then().statusCode(401);
        given().when().delete("/api/v1/stub/faults").then().statusCode(401);
    }

    @Test
    /** A console token is refused at the audience check while console tokens are not accepted (D-007 pending). */
    void invalidTokensGet401() {
        for (String token : new String[] {TestTokens.wrongAudience(), TestTokens.wrongKey(), TestTokens.expired(), TestTokens.idToken(), "garbage",
            TestTokens.console("console-viewer")}) {
            given().auth().oauth2(token).when().get(LIST).then().statusCode(401);
        }
    }

    @Test
    void tokensWithoutTheReaderRoleGet403() {
        for (String token : new String[] {TestTokens.noRole(), TestTokens.groupsOnly(), TestTokens.otherClient(),
            TestTokens.faultOperator()}) {
            given().auth().oauth2(token).when().get(LIST).then().statusCode(403).contentType("application/problem+json")
                    .body("code", is("FORBIDDEN"));
        }
    }

    @Test
    void readerPassesAuthorization() {
        given().auth().oauth2(TestTokens.reader()).when().get(LIST).then().statusCode(503)
                .body("code", is("STORE_UNAVAILABLE"));
    }

    @Test
    void readerCannotSwitchFaultsAndFaultControlIsRefusedOutsideTheAllowlist() {
        given().auth().oauth2(TestTokens.reader()).contentType(ContentType.JSON)
                .body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60}").when().post("/api/v1/stub/faults")
                .then().statusCode(403);
        // The test profile has fault control disabled and no pod namespace: refused before any state change.
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON)
                .body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60}").when().post("/api/v1/stub/faults")
                .then().statusCode(403).body("code", is("FAULTS_DISABLED"));
        given().auth().oauth2(TestTokens.faultOperator()).when().delete("/api/v1/stub/faults")
                .then().statusCode(403).body("code", is("FAULTS_DISABLED"));
    }

    @Test
    void smokePathsStayAnonymousAndOtherPathsAreDenied() {
        given().when().get("/api/greetings?name=OpenShift").then().statusCode(200);
        given().queryParam("name", " ").when().get("/api/greetings").then().statusCode(400);
        given().when().get("/api/info").then().statusCode(200);
        given().when().get("/q/health/ready").then().statusCode(200);
        given().when().get("/q/openapi").then().statusCode(200);
        given().when().get("/q/metrics").then().statusCode(200);
        given().when().get("/internal/anything").then().statusCode(not(is(200)));
    }
}
