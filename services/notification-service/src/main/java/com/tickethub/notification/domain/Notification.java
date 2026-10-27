package com.tickethub.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One row per event id. UNIQUE(event_id) plus the inbox table make duplicate deliveries harmless. */
@Entity
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_event", columnNames = "event_id"),
        indexes = @Index(name = "idx_notification_booking", columnList = "booking_id"))
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    /** PENDING -> SENDING -> SENT | RETRYING (provider down, will retry) | FAILED (dead-lettered) | REPLAYED (rebuilt, not sent). */
    public enum Status { PENDING, SENDING, SENT, RETRYING, FAILED, REPLAYED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "event_type", length = 40)
    private String eventType;

    @Column(nullable = false, length = 200)
    private String recipient;

    @Column(nullable = false, length = 200)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sent_at")
    private Instant sentAt;
}
