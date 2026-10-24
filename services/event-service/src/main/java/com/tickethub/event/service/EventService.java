package com.tickethub.event.service;

import com.tickethub.common.error.BusinessException;
import com.tickethub.event.domain.Event;
import com.tickethub.event.domain.Show;
import com.tickethub.event.dto.Dtos.EventRequest;
import com.tickethub.event.dto.Dtos.EventResponse;
import com.tickethub.event.repo.EventRepository;
import com.tickethub.event.repo.ShowRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EventService {

    private final EventRepository events;
    private final ShowRepository shows;
    private final PosterStorage posters;

    public EventService(EventRepository events, ShowRepository shows, PosterStorage posters) {
        this.events = events;
        this.shows = shows;
        this.posters = posters;
    }

    @Transactional
    public EventResponse create(EventRequest req) {
        Event e = new Event();
        apply(e, req);
        return toResponse(events.save(e));
    }

    @Transactional
    public EventResponse update(Long id, EventRequest req) {
        Event e = find(id);
        if (e.getStatus() == Event.Status.CANCELLED) {
            throw BusinessException.conflict("EVENT_CANCELLED", "A cancelled event cannot be edited");
        }
        apply(e, req);
        return toResponse(e);
    }

    @Transactional
    public EventResponse publish(Long id) {
        Event e = find(id);
        if (e.getStatus() != Event.Status.DRAFT) {
            throw BusinessException.conflict("INVALID_EVENT_STATE", "Only a DRAFT event can be published (current: " + e.getStatus() + ")");
        }
        e.setStatus(Event.Status.PUBLISHED);
        return toResponse(e);
    }

    /** Cancelling an event cancels all of its shows that have not completed. */
    @Transactional
    public EventResponse cancel(Long id) {
        Event e = find(id);
        if (e.getStatus() == Event.Status.CANCELLED) {
            return toResponse(e);
        }
        e.setStatus(Event.Status.CANCELLED);
        for (Show s : shows.findByEventId(id)) {
            if (s.getStatus() != Show.Status.COMPLETED) {
                s.setStatus(Show.Status.CANCELLED);
            }
        }
        return toResponse(e);
    }

    @Transactional(readOnly = true)
    public Page<EventResponse> listPublished(String category, String q, Pageable pageable) {
        return events.search(Event.Status.PUBLISHED, blankToNull(category), blankToNull(q), pageable).map(this::toResponse);
    }

    /** Customers only see PUBLISHED events; admins can see any. */
    @Transactional(readOnly = true)
    public EventResponse get(Long id, boolean admin) {
        Event e = find(id);
        if (!admin && e.getStatus() != Event.Status.PUBLISHED) {
            throw BusinessException.notFound("EVENT_NOT_FOUND", "Event not found");
        }
        return toResponse(e);
    }

    @Transactional
    public EventResponse uploadPoster(Long id, MultipartFile file) {
        Event e = find(id);
        e.setPosterKey(posters.upload(id, file));
        return toResponse(e);
    }

    private Event find(Long id) {
        return events.findById(id).orElseThrow(() -> BusinessException.notFound("EVENT_NOT_FOUND", "Event not found"));
    }

    private void apply(Event e, EventRequest r) {
        e.setTitle(r.title().trim());
        e.setDescription(r.description());
        e.setCategory(r.category().trim());
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    EventResponse toResponse(Event e) {
        return new EventResponse(e.getId(), e.getTitle(), e.getDescription(), e.getCategory(),
                e.getStatus().name(), posters.presignedUrl(e.getPosterKey()), e.getCreatedAt());
    }
}
