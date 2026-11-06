package org.box1.notification.domain;

/** Fixed failure codes. Provider response text is never stored or published. */
public enum FailureCode {
    PROVIDER_UNAVAILABLE,
    PROVIDER_TIMEOUT,
    PROVIDER_REJECTED,
    PROVIDER_ERROR
}
