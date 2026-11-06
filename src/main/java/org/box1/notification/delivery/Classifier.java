package org.box1.notification.delivery;

import org.box1.notification.domain.FailureCode;

/** Outcome classes (decision D-004). */
public final class Classifier {
    private Classifier() {}

    /** Outcome of one attempt. */
    public enum Outcome { SUCCESS, TRANSIENT, PERMANENT }

    public static Outcome classify(ProviderResult result) {
        return switch (result.kind()) {
            case ACCEPTED -> Outcome.SUCCESS;
            case TIMEOUT, ERROR -> Outcome.TRANSIENT;
            case HTTP_ERROR -> {
                int status = result.httpStatus();
                if (status == 408 || status == 429 || status >= 500) {
                    yield Outcome.TRANSIENT;
                }
                yield status >= 400 ? Outcome.PERMANENT : Outcome.TRANSIENT;
            }
        };
    }

    public static FailureCode code(ProviderResult result) {
        return switch (result.kind()) {
            case ACCEPTED -> null;
            case TIMEOUT -> FailureCode.PROVIDER_TIMEOUT;
            case ERROR -> FailureCode.PROVIDER_ERROR;
            case HTTP_ERROR -> {
                int status = result.httpStatus();
                if (status == 500) {
                    yield FailureCode.PROVIDER_ERROR;
                }
                if (status >= 500 || status == 408 || status == 429) {
                    yield FailureCode.PROVIDER_UNAVAILABLE;
                }
                yield status >= 400 ? FailureCode.PROVIDER_REJECTED : FailureCode.PROVIDER_ERROR;
            }
        };
    }
}
