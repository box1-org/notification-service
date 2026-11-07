package org.box1.notification.delivery;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.StubStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The single place the delivery worker calls a provider:
 *
 * <pre>
 * delivery worker → existing receipt? → bounded fault decision → configured provider (mailhog or stub)
 * </pre>
 *
 * An accepted notification returns its receipt and is never sent again. An active delivery fault fails the attempt
 * before the provider is called, so a failed attempt never creates a message; a SLOW fault delays and then delivers.
 * A notification whose previous call is still running (the worker timed out on it) is not sent a second time.
 */
@ApplicationScoped
public class ProviderBoundary {
    private static final Logger LOG = Logger.getLogger(ProviderBoundary.class);
    private final StubStore store;
    private final DeliveryProvider provider;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ProviderBoundary(StubStore store, StubProvider stub, MailHogProvider mailhog,
            @ConfigProperty(name = "notification.provider") String provider) {
        this.store = store;
        this.provider = switch (provider) {
            case StubProvider.NAME -> stub;
            case MailHogProvider.NAME -> mailhog.validated();
            default -> throw new IllegalStateException("notification.provider must be mailhog or stub");
        };
    }

    public String name() {
        return provider.name();
    }

    public ProviderResult deliver(Delivery delivery) {
        if (!inFlight.add(delivery.notificationId())) {
            // The earlier call may still be accepted; it writes the receipt that the next attempt then finds.
            LOG.info("Previous provider call still running; attempt counted as a timeout");
            return ProviderResult.timeout();
        }
        try {
            Optional<String> existing = store.receiptFor(delivery.notificationId());
            if (existing.isPresent()) {
                return ProviderResult.accepted(existing.get());
            }
            Optional<StubStore.Fault> fault = store.takeFault();
            if (fault.isPresent()) {
                switch (fault.get().mode()) {
                    case "TRANSIENT_503" -> {
                        return ProviderResult.status(503);
                    }
                    case "PERMANENT_4XX" -> {
                        return ProviderResult.status(422);
                    }
                    case "SLOW" -> pause(fault.get().delayMillis());
                    default -> {
                        return ProviderResult.status(500);
                    }
                }
            }
            return provider.deliver(delivery);
        } finally {
            inFlight.remove(delivery.notificationId());
        }
    }

    private static void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
