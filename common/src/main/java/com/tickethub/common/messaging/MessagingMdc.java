package com.tickethub.common.messaging;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

/** Puts the correlation id (from the Kafka header) and booking id into the log context while a message is processed. */
public final class MessagingMdc {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private MessagingMdc() { }

    public static void run(byte[] correlationHeader, String bookingId, Runnable work) {
        String previousCorrelation = MDC.get("correlationId");
        if (correlationHeader != null && correlationHeader.length > 0) {
            MDC.put("correlationId", new String(correlationHeader, StandardCharsets.UTF_8));
        }
        if (bookingId != null) {
            MDC.put("bookingId", bookingId);
        }
        try {
            work.run();
        } finally {
            MDC.remove("bookingId");
            if (previousCorrelation == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previousCorrelation);
            }
        }
    }
}
