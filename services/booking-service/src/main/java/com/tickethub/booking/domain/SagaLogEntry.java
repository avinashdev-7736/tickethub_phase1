package com.tickethub.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One row per state change of a booking: answers "what happened to booking X and why?". */
@Entity
@Table(name = "saga_log", indexes = @Index(name = "idx_saga_booking", columnList = "booking_id,id"))
@Getter
@Setter
@NoArgsConstructor
public class SagaLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(length = 300)
    private String reason;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();
}
