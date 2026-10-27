package com.tickethub.common.messaging;

/** The message itself is bad (malformed, missing fields). Retrying can never help: send it straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
