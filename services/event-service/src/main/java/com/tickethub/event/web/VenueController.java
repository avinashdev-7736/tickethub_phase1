package com.tickethub.event.web;

import com.tickethub.event.dto.Dtos.VenueRequest;
import com.tickethub.event.dto.Dtos.VenueResponse;
import com.tickethub.event.service.VenueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/venues")
public class VenueController {

    private final VenueService service;

    public VenueController(VenueService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenueResponse create(@Valid @RequestBody VenueRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public VenueResponse update(@PathVariable Long id, @Valid @RequestBody VenueRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/{id}")
    public VenueResponse get(@PathVariable Long id) {
        return service.get(id);
    }
}
