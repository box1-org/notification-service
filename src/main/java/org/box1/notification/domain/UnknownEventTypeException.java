package org.box1.notification.domain;

/**
 * A well-formed envelope whose event type this service does not know. A future additive type on a consumed topic is
 * acknowledged and counted, not dead-lettered, so a new producer feature cannot flood the dead-letter topic.
 */
public class UnknownEventTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnknownEventTypeException() {
        super("Unknown eventType");
    }
}
