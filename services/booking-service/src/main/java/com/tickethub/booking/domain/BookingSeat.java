package com.tickethub.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Snapshot of which seats a booking was for (kept for history even after seats are released). */
@Entity
@Table(name = "booking_seat", indexes = @Index(name = "idx_bseat_booking", columnList = "booking_id"))
@Getter
@Setter
@NoArgsConstructor
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "seat_label", nullable = false, length = 20)
    private String seatLabel;
}
