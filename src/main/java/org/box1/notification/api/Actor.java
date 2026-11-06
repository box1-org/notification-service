package org.box1.notification.api;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Who made a fault-control request: the username for people, and the immutable subject for tracing it later. */
public record Actor(String name, String subject) {
    static final Actor ANONYMOUS = new Actor("anonymous", null);

    /** Never an email address: a username that looks like one is replaced by the subject. */
    static Actor of(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return ANONYMOUS;
        }
        String subject = cut(token.getSubject());
        Object raw = token.getClaim("preferred_username");
        String name = raw instanceof jakarta.json.JsonString json ? json.getString() : raw == null ? null : raw.toString();
        if (name == null || name.isBlank() || name.contains("@")) {
            name = subject == null ? "unknown" : subject;
        }
        return new Actor(cut(name), subject);
    }

    private static String cut(String value) {
        return value == null || value.length() <= 128 ? value : value.substring(0, 128);
    }
}
