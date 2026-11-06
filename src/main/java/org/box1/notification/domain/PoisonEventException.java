package org.box1.notification.domain;

/** A record the service can never process. Messages are fixed text that names a rule, never a value: they reach dead-letter headers. */
public class PoisonEventException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PoisonEventException(String message) {
        super(message);
    }
}
