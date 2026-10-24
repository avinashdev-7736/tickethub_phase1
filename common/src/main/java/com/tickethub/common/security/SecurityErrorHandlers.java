package com.tickethub.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;

/** Writes 401 / 403 in the standard ApiError format. */
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public SecurityErrorHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        write(req, res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required or the token is invalid");
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        write(req, res, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    private void write(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String code, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), code, message, req.getRequestURI(), MDC.get("correlationId"), null));
    }
}
