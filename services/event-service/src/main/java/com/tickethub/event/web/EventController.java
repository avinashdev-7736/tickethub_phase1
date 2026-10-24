package com.tickethub.event.web;

import com.tickethub.common.security.CurrentUser;
import com.tickethub.event.dto.Dtos.EventRequest;
import com.tickethub.event.dto.Dtos.EventResponse;
import com.tickethub.event.service.EventService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @GetMapping
    public Page<EventResponse> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        return service.listPublished(category, q, PageRequest.of(Math.max(page, 0), safeSize, Sort.by("id").descending()));
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id, Authentication auth) {
        return service.get(id, CurrentUser.isAdmin(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody EventRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public EventResponse update(@PathVariable Long id, @Valid @RequestBody EventRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/publish")
    public EventResponse publish(@PathVariable Long id) {
        return service.publish(id);
    }

    @PostMapping("/{id}/cancel")
    public EventResponse cancel(@PathVariable Long id) {
        return service.cancel(id);
    }

    @PostMapping(value = "/{id}/poster", consumes = "multipart/form-data")
    public EventResponse poster(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return service.uploadPoster(id, file);
    }
}
