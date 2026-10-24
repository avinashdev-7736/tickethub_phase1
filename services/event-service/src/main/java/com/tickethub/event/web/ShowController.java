package com.tickethub.event.web;

import com.tickethub.common.security.CurrentUser;
import com.tickethub.event.dto.Dtos.SeatMapEntry;
import com.tickethub.event.dto.Dtos.ShowRequest;
import com.tickethub.event.dto.Dtos.ShowResponse;
import com.tickethub.event.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    @GetMapping
    public Page<ShowResponse> list(@RequestParam(required = false) Long eventId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        return service.listVisible(eventId, from, to, PageRequest.of(Math.max(page, 0), safeSize, Sort.by("startTime").ascending()));
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable Long id, Authentication auth) {
        return service.get(id, CurrentUser.isAdmin(auth));
    }

    @GetMapping("/{showId}/seats")
    public List<SeatMapEntry> seats(@PathVariable Long showId, Authentication auth) {
        return service.seatMap(showId, CurrentUser.isAdmin(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody ShowRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ShowResponse update(@PathVariable Long id, @Valid @RequestBody ShowRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/open")
    public ShowResponse open(@PathVariable Long id) {
        return service.open(id);
    }

    @PostMapping("/{id}/cancel")
    public ShowResponse cancel(@PathVariable Long id) {
        return service.cancel(id);
    }
}
