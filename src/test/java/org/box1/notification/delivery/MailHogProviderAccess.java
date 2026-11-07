package org.box1.notification.delivery;

import jakarta.mail.MessagingException;

/** Test access to the provider's package-private outcome mapping. */
public final class MailHogProviderAccess {
    private MailHogProviderAccess() {}

    public static ProviderResult classify(MessagingException error) {
        return MailHogProvider.classify(error);
    }
}
