package com.tickethub.event.web;

import com.tickethub.event.dto.Dtos.InternalShow;
import com.tickethub.event.service.ShowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (guarded by the internal key; never routed by the gateway). */
@RestController
@RequestMapping("/internal/shows")
public class InternalShowController {

    private final ShowService service;

    public InternalShowController(ShowService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public InternalShow get(@PathVariable Long id) {
        return service.internalView(id);
    }
}
