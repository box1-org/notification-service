package org.box1.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.notification.store.StubStore;

/**
 * A test double that writes a deterministic, redacted receipt (a body hash and length, never the body) and sends
 * nothing. The cluster uses {@link MailHogProvider}; the stub stays for tests and as a fallback.
 */
@ApplicationScoped
public class StubProvider implements DeliveryProvider {
    public static final String NAME = "stub";
    private final StubStore store;

    public StubProvider(StubStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderResult deliver(Delivery delivery) {
        byte[] bytes = delivery.body().getBytes(StandardCharsets.UTF_8);
        String bodySha = sha256(bytes);
        String written = store.writeReceipt(new StubStore.NewReceipt(receiptId(delivery, bodySha), delivery.notificationId(),
                NAME, delivery.channel(), bodySha, bytes.length, null, null));
        // Null: the notification became terminal while this call was in flight; nothing was delivered.
        return written == null ? ProviderResult.status(409) : ProviderResult.accepted(written);
    }

    /** Deterministic per notification, template and body, whichever provider accepted it. */
    static String receiptId(Delivery delivery, String bodySha) {
        return "rcpt_" + sha256((delivery.notificationId() + delivery.templateVersion() + bodySha)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
