package com.tickethub.common.error;

import java.time.Instant;
import java.util.List;

/** The single error format returned by every TicketHub service. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<String> details) {
}
