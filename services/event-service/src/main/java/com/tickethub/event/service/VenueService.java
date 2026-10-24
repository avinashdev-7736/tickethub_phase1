package com.tickethub.event.service;

import com.tickethub.common.error.BusinessException;
import com.tickethub.event.domain.Venue;
import com.tickethub.event.domain.VenueSeat;
import com.tickethub.event.dto.Dtos.SeatSpec;
import com.tickethub.event.dto.Dtos.SeatView;
import com.tickethub.event.dto.Dtos.VenueRequest;
import com.tickethub.event.dto.Dtos.VenueResponse;
import com.tickethub.event.repo.ShowRepository;
import com.tickethub.event.repo.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class VenueService {

    private final VenueRepository venues;
    private final ShowRepository shows;

    public VenueService(VenueRepository venues, ShowRepository shows) {
        this.venues = venues;
        this.shows = shows;
    }

    @Transactional
    public VenueResponse create(VenueRequest req) {
        if (req.seats() == null || req.seats().isEmpty()) {
            throw BusinessException.badRequest("SEATS_REQUIRED", "A venue needs at least one seat");
        }
        Venue v = new Venue();
        v.setName(req.name().trim());
        v.setCity(req.city().trim());
        v.setAddress(req.address());
        addSeats(v, req.seats());
        return toResponse(venues.save(v));
    }

    /** Seat layout can only change while no show uses the venue (existing reservations refer to seat ids). */
    @Transactional
    public VenueResponse update(Long id, VenueRequest req) {
        Venue v = find(id);
        v.setName(req.name().trim());
        v.setCity(req.city().trim());
        v.setAddress(req.address());
        if (req.seats() != null && !req.seats().isEmpty()) {
            if (shows.existsByVenueId(id)) {
                throw BusinessException.conflict("VENUE_IN_USE", "Seat layout cannot change once shows exist for this venue");
            }
            v.getSeats().clear();
            venues.flush();
            addSeats(v, req.seats());
        }
        return toResponse(v);
    }

    @Transactional(readOnly = true)
    public VenueResponse get(Long id) {
        return toResponse(find(id));
    }

    private Venue find(Long id) {
        return venues.findById(id).orElseThrow(() -> BusinessException.notFound("VENUE_NOT_FOUND", "Venue not found"));
    }

    private void addSeats(Venue v, List<SeatSpec> specs) {
        Set<String> seen = new HashSet<>();
        for (SeatSpec s : specs) {
            String label = s.row().trim().toUpperCase() + s.number();
            if (!seen.add(label)) {
                throw BusinessException.badRequest("DUPLICATE_SEAT", "Duplicate seat in layout: " + label);
            }
            VenueSeat seat = new VenueSeat();
            seat.setRowLabel(s.row().trim().toUpperCase());
            seat.setSeatNumber(s.number());
            seat.setSeatType(s.type());
            v.addSeat(seat);
        }
    }

    VenueResponse toResponse(Venue v) {
        List<SeatView> seats = v.getSeats().stream()
                .map(s -> new SeatView(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.label(), s.getSeatType().name()))
                .toList();
        return new VenueResponse(v.getId(), v.getName(), v.getCity(), v.getAddress(), seats.size(), seats);
    }
}
