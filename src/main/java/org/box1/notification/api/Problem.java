package org.box1.notification.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RFC 9457 problem body, with a stable machine-readable code. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String code) {
    public static final String MEDIA_TYPE = "application/problem+json";

    static Problem of(int status, String title, String detail, String code) {
        return new Problem("about:blank", title, status, detail, code);
    }
}
