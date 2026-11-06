package org.box1.notification.domain;

/** A client error with a fixed, value-free message, mapped to RFC 9457. */
public class RequestProblem extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String title;
    private final String code;

    public RequestProblem(int status, String title, String code, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String code() {
        return code;
    }
}
