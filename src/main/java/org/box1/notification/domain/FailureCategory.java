package org.box1.notification.domain;

/** Why a notification ended FAILED. */
public enum FailureCategory {
    RETRIES_EXHAUSTED,
    PERMANENT_REJECTION
}
