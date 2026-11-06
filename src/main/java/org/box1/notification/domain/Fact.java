package org.box1.notification.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A business fact worth a notice. The dedupe key comes from the fact, never from the event that reported it, so the
 * same fact from a redelivery or from a second event type maps to the same notification.
 */
public record Fact(NoticeType noticeType, String orderId, String dedupeKey) {

    /** Stable ID derived from the dedupe key, so every report of a fact names the same notification. */
    public String notificationId() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(dedupeKey.getBytes(StandardCharsets.UTF_8));
            return "ntf_" + HexFormat.of().formatHex(digest).substring(0, 26);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
