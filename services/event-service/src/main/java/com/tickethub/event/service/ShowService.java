package com.tickethub.event.service;

import com.tickethub.common.error.BusinessException;
import com.tickethub.event.client.BookingClient;
import com.tickethub.event.domain.Event;
import com.tickethub.event.domain.Show;
import com.tickethub.event.domain.Venue;
import com.tickethub.event.domain.VenueSeat;
import com.tickethub.event.dto.Dtos.*;
import com.tickethub.event.repo.EventRepository;
import com.tickethub.event.repo.ShowRepository;
import com.tickethub.event.repo.VenueRepository;
import com.tickethub.event.repo.VenueSeatRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class ShowService {

    private static final java.util.Set<Show.Status> CUSTOMER_VISIBLE = EnumSet.of(Show.Status.OPEN, Show.Status.SOLD_OUT);

    private final ShowRepository shows;
    private final EventRepository events;
    private final VenueRepository venues;
    private final VenueSeatRepository venueSeats;
    private final BookingClient bookingClient;

    public ShowService(ShowRepository shows, EventRepository events, VenueRepository venues,
                       VenueSeatRepository venueSeats, BookingClient bookingClient) {
        this.shows = shows;
        this.events = events;
        this.venues = venues;
        this.venueSeats = venueSeats;
        this.bookingClient = bookingClient;
    }

    public ShowResponse create(ShowRequest req) {
        Event event = events.findById(req.eventId())
                .orElseThrow(() -> BusinessException.notFound("EVENT_NOT_FOUND", "Event not found"));
        if (event.getStatus() == Event.Status.CANCELLED) {
            throw BusinessException.conflict("EVENT_CANCELLED", "Cannot add a show to a cancelled event");
        }
        Venue venue = venues.findById(req.venueId())
                .orElseThrow(() -> BusinessException.notFound("VENUE_NOT_FOUND", "Venue not found"));
        validateTimes(req);
        Show s = new Show();
        s.setEvent(event);
        s.setVenue(venue);
        apply(s, req);
        s.setStatus(Show.Status.SCHEDULED);
        return toResponse(shows.save(s));
    }

    public ShowResponse update(Long id, ShowRequest req) {
        Show s = shows.findDetailedById(id).orElseThrow(() -> BusinessException.notFound("SHOW_NOT_FOUND", "Show not found"));
        if (s.getStatus() != Show.Status.SCHEDULED) {
            throw BusinessException.conflict("INVALID_SHOW_STATE", "Only a SCHEDULED show can be edited (current: " + s.getStatus() + ")");
        }
        if (!s.getEvent().getId().equals(req.eventId()) || !s.getVenue().getId().equals(req.venueId())) {
            throw BusinessException.badRequest("SHOW_REASSIGN_NOT_ALLOWED", "Event and venue of a show cannot be changed; create a new show");
        }
        validateTimes(req);
        apply(s, req);
        return toResponse(s);
    }

    /** SCHEDULED -> OPEN, only for a PUBLISHED event and a future show. */
    public ShowResponse open(Long id) {
        Show s = shows.findDetailedById(id).orElseThrow(() -> BusinessException.notFound("SHOW_NOT_FOUND", "Show not found"));
        if (s.getStatus() != Show.Status.SCHEDULED) {
            throw BusinessException.conflict("INVALID_SHOW_STATE", "Only a SCHEDULED show can be opened (current: " + s.getStatus() + ")");
        }
        if (s.getEvent().getStatus() != Event.Status.PUBLISHED) {
            throw BusinessException.conflict("EVENT_NOT_PUBLISHED", "The event must be PUBLISHED before its shows can open");
        }
        if (!s.getStartTime().isAfter(Instant.now())) {
            throw BusinessException.conflict("SHOW_IN_PAST", "A show that has already started cannot be opened");
        }
        s.setStatus(Show.Status.OPEN);
        return toResponse(s);
    }

    public ShowResponse cancel(Long id) {
        Show s = shows.findDetailedById(id).orElseThrow(() -> BusinessException.notFound("SHOW_NOT_FOUND", "Show not found"));
        if (s.getStatus() == Show.Status.COMPLETED) {
            throw BusinessException.conflict("INVALID_SHOW_STATE", "A completed show cannot be cancelled");
        }
        s.setStatus(Show.Status.CANCELLED);
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public Page<ShowResponse> listVisible(Long eventId, Instant from, Instant to, Pageable pageable) {
        return shows.findVisible(Event.Status.PUBLISHED, CUSTOMER_VISIBLE, Instant.now(), eventId, from, to, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ShowResponse get(Long id, boolean admin) {
        return toResponse(visibleShow(id, admin));
    }

    /**
     * Seat map = venue layout (owned here) + reservation state (owned by Booking).
     * Runs without a DB transaction so no connection is held during the remote call.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<SeatMapEntry> seatMap(Long showId, boolean admin) {
        Show s = visibleShow(showId, admin);
        Long venueId = s.getVenue().getId();
        List<VenueSeat> layout = venueSeats.findByVenueIdOrderByRowLabelAscSeatNumberAsc(venueId);
        Map<Long, String> state = bookingClient.reservedSeats(showId);
        return layout.stream()
                .map(v -> new SeatMapEntry(v.getId(), v.getRowLabel(), v.getSeatNumber(), v.label(),
                        v.getSeatType().name(), state.getOrDefault(v.getId(), "AVAILABLE")))
                .toList();
    }

    /** What Booking needs to validate and price a reservation. */
    @Transactional(readOnly = true)
    public InternalShow internalView(Long id) {
        Show s = shows.findDetailedById(id).orElseThrow(() -> BusinessException.notFound("SHOW_NOT_FOUND", "Show not found"));
        List<InternalSeat> seats = venueSeats.findByVenueIdOrderByRowLabelAscSeatNumberAsc(s.getVenue().getId()).stream()
                .map(v -> new InternalSeat(v.getId(), v.label())).toList();
        return new InternalShow(s.getId(), s.getStatus().name(), s.getEvent().getTitle(), s.getStartTime(),
                s.getPrice(), s.getCurrency(), seats);
    }

    private Show visibleShow(Long id, boolean admin) {
        Show s = shows.findDetailedById(id).orElseThrow(() -> BusinessException.notFound("SHOW_NOT_FOUND", "Show not found"));
        if (!admin && s.getEvent().getStatus() == Event.Status.DRAFT) {
            throw BusinessException.notFound("SHOW_NOT_FOUND", "Show not found");
        }
        return s;
    }

    private void validateTimes(ShowRequest r) {
        if (!r.startTime().isAfter(Instant.now())) {
            throw BusinessException.badRequest("INVALID_SHOW_TIME", "Show start time must be in the future");
        }
        if (!r.endTime().isAfter(r.startTime())) {
            throw BusinessException.badRequest("INVALID_SHOW_TIME", "Show end time must be after the start time");
        }
    }

    private void apply(Show s, ShowRequest r) {
        s.setStartTime(r.startTime());
        s.setEndTime(r.endTime());
        s.setPrice(r.price());
        s.setCurrency(r.currency().toUpperCase());
    }

    ShowResponse toResponse(Show s) {
        return new ShowResponse(s.getId(), s.getEvent().getId(), s.getEvent().getTitle(), s.getVenue().getId(),
                s.getVenue().getName(), s.getStartTime(), s.getEndTime(), s.getPrice(), s.getCurrency(), s.getStatus().name());
    }
}
