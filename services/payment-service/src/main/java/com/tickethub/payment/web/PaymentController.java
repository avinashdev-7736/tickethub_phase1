package com.tickethub.payment.web;

import com.tickethub.common.security.CurrentUser;
import com.tickethub.payment.dto.Dtos.InitiatePaymentRequest;
import com.tickethub.payment.dto.Dtos.PaymentResponse;
import com.tickethub.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse initiate(@Valid @RequestBody InitiatePaymentRequest req, @AuthenticationPrincipal Jwt jwt) {
        return service.initiate(req.bookingId(), CurrentUser.id(jwt));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(id, CurrentUser.id(jwt));
    }

    /** Provider callback: public route, authenticated by signature verification instead of a JWT. */
    @PostMapping("/webhook")
    public Map<String, String> webhook(@RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
        service.handleWebhook(rawBody, headers);
        return Map.of("status", "received");
    }
}
