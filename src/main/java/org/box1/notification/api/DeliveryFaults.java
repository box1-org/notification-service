package org.box1.notification.api;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.delivery.FaultGate;
import org.box1.notification.delivery.ProviderBoundary;
import org.box1.notification.domain.RequestProblem;
import org.box1.notification.ops.NotificationMetrics;
import org.box1.notification.store.StoreException;
import org.box1.notification.store.StubStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Bounded, audited delivery fault control (decision D-009), shared by {@code /api/v1/delivery-faults} and its
 * compatibility alias {@code /api/v1/stub}. Faults act at the provider boundary, in front of whichever provider is
 * configured. Every bound is enforced here, on the server, and every request, accepted or refused, is audited with the
 * actor from the token.
 */
@ApplicationScoped
public class DeliveryFaults {
    /** Compiled hard caps; configuration can only lower the TTL cap. */
    public static final int MAX_TTL_CAP_SECONDS = 900;
    public static final int MIN_TTL_SECONDS = 10;
    public static final int MAX_DELAY_MILLIS = 5000;
    public static final int MIN_DELAY_MILLIS = 100;
    public static final int MAX_FAIL_COUNT = 20;
    private static final Logger LOG = Logger.getLogger(DeliveryFaults.class);
    private static final Set<String> MODES = Set.of("TRANSIENT_503", "SLOW", "PERMANENT_4XX");

    private final StubStore store;
    private final FaultGate gate;
    private final NotificationMetrics metrics;
    private final ProviderBoundary provider;
    private final int maxTtlSeconds;

    public DeliveryFaults(StubStore store, FaultGate gate, NotificationMetrics metrics, ProviderBoundary provider,
            @ConfigProperty(name = "notification.stub.max-ttl-seconds") int maxTtlSeconds) {
        if (maxTtlSeconds < MIN_TTL_SECONDS || maxTtlSeconds > MAX_TTL_CAP_SECONDS) {
            throw new IllegalStateException("notification.stub.max-ttl-seconds must be between " + MIN_TTL_SECONDS
                    + " and " + MAX_TTL_CAP_SECONDS);
        }
        this.store = store;
        this.gate = gate;
        this.metrics = metrics;
        this.provider = provider;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public Views.StubState state() {
        String refusal = gate.refusal();
        return new Views.StubState(provider.name(), store.activeFault().map(Views.ActiveFault::of).orElse(null),
                new Views.FaultControl(refusal == null, refusal, gate.namespace(), maxTtlSeconds));
    }

    public Views.StubState set(Views.FaultRequest request, Actor actor) {
        String mode = request == null ? null : request.mode();
        Integer ttl = request == null ? null : request.ttlSeconds();
        String refusal = gate.refusal();
        if (refusal != null) {
            throw refuse("SET", mode, ttl, refusal, actor);
        }
        if (mode == null || !MODES.contains(mode)) {
            throw refuse("SET", null, ttl, "INVALID_MODE", actor);
        }
        if (ttl == null || ttl < MIN_TTL_SECONDS || ttl > maxTtlSeconds) {
            throw refuse("SET", mode, ttl, "INVALID_TTL", actor);
        }
        int delay = 0;
        if ("SLOW".equals(mode)) {
            delay = request.delayMillis() == null ? 3000 : request.delayMillis();
            if (delay < MIN_DELAY_MILLIS || delay > MAX_DELAY_MILLIS) {
                throw refuse("SET", mode, ttl, "INVALID_DELAY", actor);
            }
        } else if (request.delayMillis() != null) {
            throw refuse("SET", mode, ttl, "INVALID_DELAY", actor);
        }
        Integer failCount = request.failCount();
        if (failCount != null && (failCount < 1 || failCount > MAX_FAIL_COUNT)) {
            throw refuse("SET", mode, ttl, "INVALID_FAIL_COUNT", actor);
        }
        store.setFault(mode, ttl, delay, failCount, actor.name(), actor.subject(), gate.namespace());
        metrics.faultControl("set", "accepted");
        return state();
    }

    public Views.StubState clear(Actor actor) {
        String refusal = gate.refusal();
        if (refusal != null) {
            throw refuse("CLEAR", null, null, refusal, actor);
        }
        store.clearFault(actor.name(), actor.subject(), gate.namespace());
        metrics.faultControl("clear", "accepted");
        return state();
    }

    public Views.Audit audit() {
        return new Views.Audit(store.audit(100));
    }

    private RequestProblem refuse(String action, String mode, Integer ttl, String code, Actor actor) {
        try {
            store.auditRefusal(actor.name(), actor.subject(), action, mode == null || MODES.contains(mode) ? mode : null, ttl,
                    "REFUSED_" + code, gate.namespace());
        } catch (StoreException error) {
            // The refusal stands even when it cannot be audited. Accepted changes are audited in their own transaction.
            LOG.warnv("Fault-control refusal not audited: code={0} error={1}", code, error.getMessage());
        }
        metrics.faultControl(action.toLowerCase(java.util.Locale.ROOT), "refused");
        boolean gateClosed = code.startsWith("FAULTS_") || code.startsWith("NAMESPACE_");
        return new RequestProblem(gateClosed ? 403 : 400, gateClosed ? "Forbidden" : "Bad Request", code,
                gateClosed ? "Delivery fault control is not allowed in this namespace" : "The fault request is out of bounds");
    }
}
