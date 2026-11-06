package org.box1.notification.ops;

import java.time.Instant;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Fails startup on configuration that would break the delivery or offset policy. */
@ApplicationScoped
public class StartupChecks {
    private static final Logger LOG = Logger.getLogger(StartupChecks.class);

    void onStart(@Observes StartupEvent event,
            @ConfigProperty(name = "notification.consumer.events-floor") Instant floor,
            @ConfigProperty(name = "notification.provider") String provider) {
        if (floor.isAfter(Instant.now())) {
            throw new IllegalStateException("NOTIFY_EVENTS_FLOOR is in the future");
        }
        if (!"stub".equals(provider)) {
            throw new IllegalStateException("Only the stub provider exists; a real provider needs approval and a secret");
        }
        LOG.infov("Events floor {0}; provider {1}", floor, provider);
    }
}
