package org.box1.notification.api;

import java.util.Set;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.box1.notification.delivery.FaultGate;
import org.box1.notification.domain.RequestProblem;
import org.box1.notification.ops.NotificationMetrics;
import org.box1.notification.store.StoreException;
import org.box1.notification.store.StubStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.jboss.logging.Logger;

/**
 * The stub provider's state and its bounded, audited fault control (decision D-009). Every bound is enforced here, on
 * the server, and every request, accepted or refused, is audited with the actor from the token.
 */
@Path("/api/v1/stub")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearer")
public class StubResource {
    /** Compiled hard caps; configuration can only lower the TTL cap. */
    public static final int MAX_TTL_CAP_SECONDS = 900;
    public static final int MIN_TTL_SECONDS = 10;
    public static final int MAX_DELAY_MILLIS = 5000;
    public static final int MIN_DELAY_MILLIS = 100;
    public static final int MAX_FAIL_COUNT = 20;
    private static final Logger LOG = Logger.getLogger(StubResource.class);
    private static final Set<String> MODES = Set.of("TRANSIENT_503", "SLOW", "PERMANENT_4XX");

    private final StubStore store;
    private final FaultGate gate;
    private final NotificationMetrics metrics;
    private final SecurityIdentity identity;
    private final int maxTtlSeconds;

    public StubResource(StubStore store, FaultGate gate, NotificationMetrics metrics, SecurityIdentity identity,
            @ConfigProperty(name = "notification.stub.max-ttl-seconds") int maxTtlSeconds) {
        if (maxTtlSeconds < MIN_TTL_SECONDS || maxTtlSeconds > MAX_TTL_CAP_SECONDS) {
            throw new IllegalStateException("notification.stub.max-ttl-seconds must be between " + MIN_TTL_SECONDS
                    + " and " + MAX_TTL_CAP_SECONDS);
        }
        this.store = store;
        this.gate = gate;
        this.metrics = metrics;
        this.identity = identity;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    @GET
    @RolesAllowed(ClientRoleAugmentor.READER)
    @Operation(summary = "Show the stub provider, its active fault and whether fault control is open here")
    public Views.StubState state() {
        return view();
    }

    /** Not annotated: set and clear return it to a fault operator who may not hold the reader role. */
    private Views.StubState view() {
        String refusal = gate.refusal();
        return new Views.StubState("stub", store.activeFault().map(Views.ActiveFault::of).orElse(null),
                new Views.FaultControl(refusal == null, refusal, gate.namespace(), maxTtlSeconds));
    }

    @POST
    @Path("/faults")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Set the stub's fault mode for a bounded time (demo namespace only)")
    public Response set(Views.FaultRequest request) {
        String actor = actor();
        String mode = request == null ? null : request.mode();
        Integer ttl = request == null ? null : request.ttlSeconds();
        String refusal = gate.refusal();
        if (refusal != null) {
            return refuse("SET", mode, ttl, refusal, actor);
        }
        if (mode == null || !MODES.contains(mode)) {
            return refuse("SET", null, ttl, "INVALID_MODE", actor);
        }
        if (ttl == null || ttl < MIN_TTL_SECONDS || ttl > maxTtlSeconds) {
            return refuse("SET", mode, ttl, "INVALID_TTL", actor);
        }
        int delay = 0;
        if ("SLOW".equals(mode)) {
            delay = request.delayMillis() == null ? 3000 : request.delayMillis();
            if (delay < MIN_DELAY_MILLIS || delay > MAX_DELAY_MILLIS) {
                return refuse("SET", mode, ttl, "INVALID_DELAY", actor);
            }
        } else if (request.delayMillis() != null) {
            return refuse("SET", mode, ttl, "INVALID_DELAY", actor);
        }
        Integer failCount = request.failCount();
        if (failCount != null && (failCount < 1 || failCount > MAX_FAIL_COUNT)) {
            return refuse("SET", mode, ttl, "INVALID_FAIL_COUNT", actor);
        }
        store.setFault(mode, ttl, delay, failCount, actor, gate.namespace());
        metrics.faultControl("set", "accepted");
        return Response.ok(view()).build();
    }

    @DELETE
    @Path("/faults")
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Clear the stub's fault mode")
    public Response clear() {
        String actor = actor();
        String refusal = gate.refusal();
        if (refusal != null) {
            return refuse("CLEAR", null, null, refusal, actor);
        }
        store.clearFault(actor, gate.namespace());
        metrics.faultControl("clear", "accepted");
        return Response.ok(view()).build();
    }

    @GET
    @Path("/faults/audit")
    @RolesAllowed(ClientRoleAugmentor.FAULT_OPERATOR)
    @Operation(summary = "Read the fault-control audit trail, newest first")
    public Views.Audit audit() {
        return new Views.Audit(store.audit(100));
    }

    private Response refuse(String action, String mode, Integer ttl, String code, String actor) {
        try {
            store.auditRefusal(actor, action, mode == null || MODES.contains(mode) ? mode : null, ttl, "REFUSED_" + code,
                    gate.namespace());
        } catch (StoreException error) {
            // The refusal stands even when it cannot be audited. Accepted changes are audited in their own transaction.
            LOG.warnv("Fault-control refusal not audited: code={0} error={1}", code, error.getMessage());
        }
        metrics.faultControl(action.toLowerCase(java.util.Locale.ROOT), "refused");
        boolean gateClosed = code.startsWith("FAULTS_") || code.startsWith("NAMESPACE_");
        throw new RequestProblem(gateClosed ? 403 : 400, gateClosed ? "Forbidden" : "Bad Request", code,
                gateClosed ? "Stub fault control is not allowed in this namespace" : "The fault request is out of bounds");
    }

    /** The operator's username from the token, else its subject. Never an email address. */
    private String actor() {
        if (identity.getPrincipal() instanceof JsonWebToken token) {
            Object name = token.getClaim("preferred_username");
            String value = name == null ? null : name.toString().replace("\"", "");
            if (value != null && !value.isBlank() && !value.contains("@")) {
                return value.length() > 128 ? value.substring(0, 128) : value;
            }
            return token.getSubject() == null ? "unknown" : token.getSubject();
        }
        return "unknown";
    }
}
