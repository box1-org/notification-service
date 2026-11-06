package org.box1.notification.delivery;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decides whether stub fault control is open (decision D-009). It opens only when it is enabled, the pod's namespace
 * is known from the downward API, and that namespace is on the allowlist the user named.
 */
@ApplicationScoped
public class FaultGate {
    private final boolean enabled;
    private final Optional<String> namespace;
    private final List<String> allowlist;

    public FaultGate(@ConfigProperty(name = "notification.stub.faults-enabled") boolean enabled,
            @ConfigProperty(name = "notification.pod-namespace") Optional<String> namespace,
            @ConfigProperty(name = "notification.stub.fault-namespaces") Optional<List<String>> allowlist) {
        this.enabled = enabled;
        this.namespace = namespace.map(String::strip).filter(value -> !value.isEmpty());
        this.allowlist = allowlist.orElse(List.of()).stream().map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    /** Null when open, else the refusal code. */
    public String refusal() {
        if (!enabled) {
            return "FAULTS_DISABLED";
        }
        if (namespace.isEmpty()) {
            return "NAMESPACE_UNKNOWN";
        }
        if (!allowlist.contains(namespace.get())) {
            return "NAMESPACE_NOT_ALLOWLISTED";
        }
        return null;
    }

    public String namespace() {
        return namespace.orElse(null);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<String> allowlist() {
        return allowlist;
    }
}
