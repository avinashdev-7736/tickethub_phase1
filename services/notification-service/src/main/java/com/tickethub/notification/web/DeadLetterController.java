package com.tickethub.notification.web;

import com.tickethub.notification.domain.DeadLetter;
import com.tickethub.notification.service.DeadLetterService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ADMIN only. Inspect dead-lettered messages and send one back after a fix. Reached directly on the service port (not via the gateway). */
@RestController
@RequestMapping("/admin/dead-letters")
public class DeadLetterController {

    public record ReplayRequest(String payload) { }

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public List<DeadLetter> list() {
        return service.list();
    }

    /** Optional body {"payload":"{...corrected json...}"} replaces the stored payload before it is sent again. */
    @PostMapping("/{id}/replay")
    public DeadLetter replay(@PathVariable Long id, @RequestBody(required = false) ReplayRequest body) {
        return service.replay(id, body == null ? null : body.payload());
    }
}
