package org.box1.notification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.jwt.build.Jwt;

/**
 * Issues tokens from a key generated for this test run only. Nothing here reaches Keycloak. The service verifies them with
 * the same issuer, audience and role rules it uses in production, and only the key location differs.
 */
public final class TestTokens implements QuarkusTestResourceLifecycleManager {
    static final String ISSUER = "https://keycloak.apps.ocp.box1.org/auth/realms/box1";
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Map<String, String> start() {
        try {
            Path pem = Files.createTempFile("notification-test-jwt", ".pem");
            String body = Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            return Map.of("mp.jwt.verify.publickey.location", pem.toUri().toString());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void stop() {
        // The key file is in the system temp directory, and holds only a public key.
    }

    static String reader() {
        return token("op-reader", "notification-service", "notification-service", List.of("notification-reader"));
    }

    static String faultOperator() {
        return token("op-faults", "notification-service", "notification-service", List.of("notification-fault-operator"));
    }

    static String readerAndFaultOperator() {
        return token("op-both", "notification-service", "notification-service",
                List.of("notification-reader", "notification-fault-operator"));
    }

    static String noRole() {
        return token("op-none", "notification-service", "notification-service", List.of("something-else"));
    }

    /** Issued to another client, with this service in its audience and these roles on it (asset-service SEC-3). */
    static String otherClient() {
        return Jwt.issuer(ISSUER).audience(java.util.Set.of("notification-service", "openshell-cli")).subject("op-other")
                .claim("azp", "openshell-cli")
                .claim("resource_access", Map.of("notification-service", Map.of("roles", List.of("notification-reader"))))
                .sign(KEYS.getPrivate());
    }

    /** The shape the operator console backend forwards (#18 D-002): azp and aud operations-console, console roles. */
    static String console(String role) {
        return Jwt.issuer(ISSUER).audience(java.util.Set.of("operations-console", "inventory-service")).subject("op-console")
                .claim("azp", "operations-console").claim("preferred_username", "console-user")
                .claim("resource_access", Map.of("operations-console", Map.of("roles", List.of(role)),
                        "notification-service", Map.of("roles", List.of("notification-fault-operator"))))
                .sign(KEYS.getPrivate());
    }

    static String wrongAudience() {
        return Jwt.issuer(ISSUER).audience("other-service").subject("op-aud").claim("azp", "notification-service")
                .claim("resource_access", Map.of("notification-service", Map.of("roles", List.of("notification-reader"))))
                .sign(KEYS.getPrivate());
    }

    static String wrongKey() {
        return Jwt.issuer(ISSUER).audience("notification-service").subject("op-key").claim("azp", "notification-service")
                .claim("resource_access", Map.of("notification-service", Map.of("roles", List.of("notification-reader"))))
                .sign(OTHER_KEYS.getPrivate());
    }

    /** Signed correctly, with the role only in a top-level groups claim. */
    static String groupsOnly() {
        return Jwt.issuer(ISSUER).audience("notification-service").subject("op-groups").claim("azp", "notification-service")
                .groups(java.util.Set.of("notification-reader")).sign(KEYS.getPrivate());
    }

    static String expired() {
        return Jwt.issuer(ISSUER).audience("notification-service").subject("op-expired").expiresAt(1000L)
                .claim("azp", "notification-service")
                .claim("resource_access", Map.of("notification-service", Map.of("roles", List.of("notification-reader"))))
                .sign(KEYS.getPrivate());
    }

    private static String token(String subject, String azp, String audience, List<String> roles) {
        return Jwt.issuer(ISSUER).audience(audience).subject(subject).claim("azp", azp)
                .claim("preferred_username", subject)
                .claim("resource_access", Map.of(azp, Map.of("roles", roles)))
                .sign(KEYS.getPrivate());
    }
}

