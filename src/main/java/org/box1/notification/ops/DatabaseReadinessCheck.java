package org.box1.notification.ops;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.NotificationStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** PostgreSQL is a readiness dependency; Kafka deliberately is not (an outage backs up in the outbox instead). */
@Readiness
@ApplicationScoped
public class DatabaseReadinessCheck implements HealthCheck {
    private final NotificationStore store;
    private final boolean enabled;

    public DatabaseReadinessCheck(NotificationStore store,
            @ConfigProperty(name = "notification.readiness.database") boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    @Override
    public HealthCheckResponse call() {
        if (!enabled) {
            return HealthCheckResponse.up("database (check disabled)");
        }
        return store.ping() ? HealthCheckResponse.up("database") : HealthCheckResponse.down("database");
    }
}
