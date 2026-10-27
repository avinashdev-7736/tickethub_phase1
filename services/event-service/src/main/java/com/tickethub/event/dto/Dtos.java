package com.tickethub.event.dto;

import com.tickethub.event.domain.VenueSeat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() { }

    // ----- events -----
    public record EventRequest(
            @NotBlank @Size(max = 150) String title,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 50) String category) { }

    public record EventResponse(Long id, String title, String description, String category,
                                String status, String posterUrl, Instant createdAt) { }

    // ----- venues -----
    public record SeatSpec(
            @NotBlank @Size(max = 5) String row,
            @Min(1) int number,
            @NotNull VenueSeat.SeatType type) { }

    public record VenueRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String city,
            @Size(max = 255) String address,
            @Valid List<SeatSpec> seats) { }

    public record SeatView(Long id, String row, int number, String label, String type) { }

    public record VenueResponse(Long id, String name, String city, String address, int seatCount, List<SeatView> seats) { }

    // ----- shows -----
    public record ShowRequest(
            @NotNull Long eventId,
            @NotNull Long venueId,
            @NotNull Instant startTime,
            @NotNull Instant endTime,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @NotBlank @Size(min = 3, max = 3) String currency) { }

    public record ShowResponse(Long id, Long eventId, String eventTitle, Long venueId, String venueName,
                               Instant startTime, Instant endTime, BigDecimal price, String currency, String status) { }

    public record SeatMapEntry(Long seatId, String row, int number, String label, String type, String status) { }

    // ----- internal (service to service) -----
    public record InternalSeat(Long id, String label, String type) { }

    public record InternalShow(Long showId, String status, String eventTitle, Instant startTime,
                               BigDecimal price, String currency, List<InternalSeat> seats) { }
}
