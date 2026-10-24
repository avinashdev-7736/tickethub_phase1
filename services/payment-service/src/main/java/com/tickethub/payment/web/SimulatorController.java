package com.tickethub.payment.web;

import com.tickethub.payment.dto.Dtos.PaymentResponse;
import com.tickethub.payment.provider.PaymentProvider;
import com.tickethub.payment.provider.SimulatedPaymentProvider;
import com.tickethub.payment.service.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** ADMIN-only controls for demos and failure-scenario testing. Exists only while payment.provider=simulated. */
@RestController
@RequestMapping("/payments/simulator")
@ConditionalOnProperty(name = "payment.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatorController {

    private final SimulatedPaymentProvider provider;
    private final PaymentService service;

    public SimulatorController(SimulatedPaymentProvider provider, PaymentService service) {
        this.provider = provider;
        this.service = service;
    }

    /** e.g. POST /payments/simulator/mode?mode=FAILURE or mode=PENDING (stuck) or &delayMs=4000 (slow provider). */
    @PostMapping("/mode")
    public Map<String, Object> mode(@RequestParam SimulatedPaymentProvider.Mode mode,
                                    @RequestParam(defaultValue = "0") long delayMs) {
        provider.configure(mode, delayMs);
        return Map.of("mode", provider.mode(), "delayMs", delayMs);
    }

    /** Complete a PENDING payment, like a provider callback would. */
    @PostMapping("/complete")
    public PaymentResponse complete(@RequestParam String paymentId, @RequestParam PaymentProvider.Status outcome,
                                    @RequestParam(required = false) String reason) {
        return service.completeManually(paymentId, outcome, reason);
    }
}
