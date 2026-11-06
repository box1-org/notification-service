package org.box1.notification.api;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Grants roles by the client the token was issued to (decision D-007). Signature, issuer, expiry and audience are already
 * verified by smallrye-jwt. Roles never come from a top-level groups claim, and a token issued to any other client gets
 * no role, even if Keycloak added this service's audience to it.
 *
 * <ul>
 * <li>{@code azp=notification-service} with {@code aud} containing it: roles from
 * {@code resource_access.notification-service.roles}.</li>
 * <li>{@code azp=<console client>} when configured, with {@code aud} containing it: {@code notification-reader} if the
 * user holds one of the configured console roles. Never fault control.</li>
 * </ul>
 */
@ApplicationScoped
public class ClientRoleAugmentor implements SecurityIdentityAugmentor {
    public static final String CLIENT = "notification-service";
    public static final String READER = "notification-reader";
    public static final String FAULT_OPERATOR = "notification-fault-operator";
    private static final Set<String> OWN_ROLES = Set.of(READER, FAULT_OPERATOR);

    private final Optional<String> consoleClient;
    private final Set<String> consoleReaderRoles;

    public ClientRoleAugmentor(@ConfigProperty(name = "notification.auth.console-client-id") Optional<String> consoleClient,
            @ConfigProperty(name = "notification.auth.console-reader-roles") List<String> consoleReaderRoles) {
        this.consoleClient = consoleClient.map(String::strip).filter(value -> !value.isEmpty() && !CLIENT.equals(value));
        this.consoleReaderRoles = Set.copyOf(consoleReaderRoles);
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(identity);
        }
        // Only access tokens: an ID token for this client carries the same aud and azp (security review L2).
        if (!"Bearer".equals(stringClaim(token, "typ"))) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Not an access token"));
        }
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder().setPrincipal(token)
                .addCredentials(identity.getCredentials()).addAttributes(identity.getAttributes())
                .addRoles(roles(token));
        return Uni.createFrom().item(builder.build());
    }

    Set<String> roles(JsonWebToken token) {
        String azp = stringClaim(token, "azp");
        Set<String> audience = token.getAudience() == null ? Set.of() : token.getAudience();
        if (CLIENT.equals(azp) && audience.contains(CLIENT)) {
            Set<String> granted = new HashSet<>(clientRoles(token, CLIENT));
            granted.retainAll(OWN_ROLES);
            return granted;
        }
        if (consoleClient.isPresent() && consoleClient.get().equals(azp) && audience.contains(azp)) {
            Set<String> held = clientRoles(token, azp);
            return held.stream().anyMatch(consoleReaderRoles::contains) ? Set.of(READER) : Set.of();
        }
        return Set.of();
    }

    private static String stringClaim(JsonWebToken token, String name) {
        Object value = token.getClaim(name);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof JsonString json) {
            return json.getString();
        }
        return null;
    }

    private static Set<String> clientRoles(JsonWebToken token, String client) {
        Object raw = token.getClaim("resource_access");
        if (!(raw instanceof JsonObject access)) {
            return Set.of();
        }
        JsonValue entry = access.get(client);
        if (!(entry instanceof JsonObject clientAccess)) {
            return Set.of();
        }
        JsonValue roles = clientAccess.get("roles");
        if (!(roles instanceof JsonArray array)) {
            return Set.of();
        }
        return array.stream().filter(JsonString.class::isInstance).map(value -> ((JsonString) value).getString())
                .collect(Collectors.toSet());
    }
}
