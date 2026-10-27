package com.tickethub.payment.provider;

/** The provider did not answer in time. We do NOT know whether the money moved: the payment becomes UNKNOWN, not FAILED. */
public class ProviderTimeoutException extends RuntimeException {

    private final String reference;

    public ProviderTimeoutException(String reference, String message, Throwable cause) {
        super(message, cause);
        this.reference = reference;
    }

    /** Provider reference if one was already created before the timeout (needed to look the payment up later). */
    public String getReference() {
        return reference;
    }
}
