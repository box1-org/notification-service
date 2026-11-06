package org.box1.notification;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Fault control enabled, but this pod's namespace is not on the allowlist: refused, audited, nothing changes. */
@QuarkusTest
@Tag("containers")
@QuarkusTestResource(value = FullStackResource.class, restrictToAnnotatedClass = true)
@TestProfile(NotAllowlistedNamespaceTest.OtherNamespace.class)
class NotAllowlistedNamespaceTest {
    /** The service runs in notification-service-dev; only run16-demo is allowlisted. */
    public static class OtherNamespace implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("notification.pod-namespace", "notification-service-dev");
        }
    }

    @Test
    void refusedAndAudited() {
        given().auth().oauth2(TestTokens.faultOperator()).contentType(ContentType.JSON)
                .body("{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":60}").when().post("/api/v1/stub/faults")
                .then().statusCode(403).body("code", is("NAMESPACE_NOT_ALLOWLISTED"));
        given().auth().oauth2(TestTokens.reader()).when().get("/api/v1/stub").then().statusCode(200)
                .body("faultControl.open", is(false)).body("faultControl.refusal", is("NAMESPACE_NOT_ALLOWLISTED"))
                .body("activeFault", is((Object) null));
        assertEquals(1L, Harness.count("SELECT count(*) FROM stub_fault_audit WHERE outcome = 'REFUSED_NAMESPACE_NOT_ALLOWLISTED'"
                + " AND actor = 'op-faults' AND namespace = 'notification-service-dev'"));
        assertEquals(0L, Harness.count("SELECT count(*) FROM stub_fault"));
    }
}
