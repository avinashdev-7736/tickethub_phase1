package com.tickethub.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Protects /internal/** (service-to-service) endpoints with a shared key.
 * The API gateway never routes /internal/**, so these are reachable only inside the network.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";

    private final byte[] key;
    private final ObjectMapper mapper;

    public InternalApiKeyFilter(String key, ObjectMapper mapper) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String provided = req.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(401);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), new ApiError(Instant.now(), 401, "Unauthorized",
                "INTERNAL_KEY_REQUIRED", "Missing or invalid internal key", req.getRequestURI(),
                MDC.get("correlationId"), null));
    }
}
