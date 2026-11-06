package org.box1.notification.delivery;

/** What a provider call returned. No provider text is kept. */
public record ProviderResult(Kind kind, Integer httpStatus, String receiptId) {
    /** Result kinds. */
    public enum Kind { ACCEPTED, HTTP_ERROR, TIMEOUT, ERROR }

    public static ProviderResult accepted(String receiptId) {
        return new ProviderResult(Kind.ACCEPTED, 202, receiptId);
    }

    public static ProviderResult status(int httpStatus) {
        return new ProviderResult(Kind.HTTP_ERROR, httpStatus, null);
    }

    public static ProviderResult timeout() {
        return new ProviderResult(Kind.TIMEOUT, null, null);
    }

    public static ProviderResult error() {
        return new ProviderResult(Kind.ERROR, null, null);
    }
}
