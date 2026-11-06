package org.box1.notification.ops;

import java.time.Instant;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.box1.notification.api.StubResource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Fails startup on configuration that would break the delivery or offset policy. */
@ApplicationScoped
public class StartupChecks {
    private static final Logger LOG = Logger.getLogger(StartupChecks.class);

    void onStart(@Observes StartupEvent event,
            @ConfigProperty(name = "notification.consumer.events-floor") Instant floor,
            @ConfigProperty(name = "notification.provider") String provider,
            @ConfigProperty(name = "notification.stub.max-ttl-seconds") int maxTtlSeconds) {
        if (maxTtlSeconds < StubResource.MIN_TTL_SECONDS || maxTtlSeconds > StubResource.MAX_TTL_CAP_SECONDS) {
            throw new IllegalStateException("notification.stub.max-ttl-seconds must be between "
                    + StubResource.MIN_TTL_SECONDS + " and " + StubResource.MAX_TTL_CAP_SECONDS);
        }
        if (floor.isAfter(Instant.now())) {
            throw new IllegalStateException("NOTIFY_EVENTS_FLOOR is in the future");
        }
        if (!"stub".equals(provider)) {
            throw new IllegalStateException("Only the stub provider exists; a real provider needs approval and a secret");
        }
        LOG.infov("Events floor {0}; provider {1}", floor, provider);
    }
}
