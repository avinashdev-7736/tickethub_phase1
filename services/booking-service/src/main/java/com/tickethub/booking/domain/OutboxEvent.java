package com.tickethub.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Transactional outbox: the event is stored in the SAME transaction as the state change that caused it.
 * A relay publishes it to Kafka afterwards, so a Kafka outage can delay an event but never lose it.
 */
@Entity
@Table(name = "outbox_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event", columnNames = "event_id"),
        indexes = @Index(name = "idx_outbox_pending", columnList = "published_at,id"))
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
