package com.tickethub.common.messaging;

/** Something outside the message failed (mail server down, provider timeout). Retrying later can succeed. */
public class TransientProcessingException extends RuntimeException {
    public TransientProcessingException(String message) {
        super(message);
    }

    public TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
