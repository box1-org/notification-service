package org.box1.notification.domain;

/** Intent lifecycle. DELIVERED and FAILED are terminal. */
public enum NotificationStatus {
    PENDING,
    RETRY_SCHEDULED,
    DELIVERED,
    FAILED;

    public boolean terminal() {
        return this == DELIVERED || this == FAILED;
    }
}
