package org.box1.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.StubStore;

/**
 * The only provider: it writes a deterministic, redacted receipt (a body hash and length, never the body) and can be
 * switched into bounded fault modes by an operator in an allowlisted namespace.
 */
@ApplicationScoped
public class StubProvider implements DeliveryProvider {
    private final StubStore store;

    public StubProvider(StubStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public ProviderResult deliver(String notificationId, String channel, String templateVersion, String body) {
        Optional<String> existing = store.receiptFor(notificationId);
        if (existing.isPresent()) {
            // Idempotent: an accepted notification is never delivered twice.
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String bodySha = sha256(bytes);
        String receiptId = "rcpt_" + sha256((notificationId + templateVersion + bodySha).getBytes(StandardCharsets.UTF_8))
                .substring(0, 24);
        String written = store.writeReceipt(receiptId, notificationId, channel, bodySha, bytes.length);
        // Null: the notification became terminal while this call was in flight; nothing was delivered.
        return written == null ? ProviderResult.status(409) : ProviderResult.accepted(written);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
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
