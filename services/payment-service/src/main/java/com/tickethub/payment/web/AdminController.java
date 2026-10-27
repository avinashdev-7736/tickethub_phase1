package com.tickethub.payment.web;

import com.tickethub.payment.dto.Dtos.FindingView;
import com.tickethub.payment.dto.Dtos.ReconciliationReport;
import com.tickethub.payment.repo.OutboxRepository;
import com.tickethub.payment.service.OutboxRelay;
import com.tickethub.payment.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** ADMIN only. Reached directly on the service port (not routed by the gateway). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ReconciliationService reconciliation;
    private final OutboxRepository outbox;
    private final OutboxRelay relay;

    public AdminController(ReconciliationService reconciliation, OutboxRepository outbox, OutboxRelay relay) {
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.relay = relay;
    }

    /** Run reconciliation now and return what was fixed and what needs a human. */
    @PostMapping("/reconciliation/run")
    public ReconciliationReport runReconciliation() {
        return reconciliation.run();
    }

    @GetMapping("/reconciliation/findings")
    public List<FindingView> findings() {
        return reconciliation.recent();
    }

    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        return Map.of("pending", outbox.countByPublishedAtIsNull());
    }

    @PostMapping("/outbox/relay")
    public Map<String, Object> relayNow() {
        return Map.of("published", relay.relayOnce());
    }
}
