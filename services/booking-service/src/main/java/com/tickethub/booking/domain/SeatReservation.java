package com.tickethub.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The live holder of a seat. The UNIQUE (show_id, seat_id) constraint is what prevents double booking:
 * the database lets exactly one concurrent insert win. Rows are deleted when a seat is released.
 */
@Entity
@Table(name = "seat_reservation",
        uniqueConstraints = @UniqueConstraint(name = "uk_show_seat", columnNames = {"show_id", "seat_id"}),
        indexes = @Index(name = "idx_resv_booking", columnList = "booking_id"))
@Getter
@Setter
@NoArgsConstructor
public class SeatReservation {

    public enum State { RESERVED, BOOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private State state = State.RESERVED;
}
