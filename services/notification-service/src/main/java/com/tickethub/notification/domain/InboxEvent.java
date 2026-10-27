package com.tickethub.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Inbox: every processed event id. It is written in the SAME database transaction as the notification record,
 * and the unique constraint rejects a second copy even when two consumer instances race.
 */
@Entity
@Table(name = "inbox_event", uniqueConstraints = @UniqueConstraint(name = "uk_inbox_event", columnNames = "event_id"))
@Getter
@Setter
@NoArgsConstructor
public class InboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", length = 40)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    public InboxEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }
}
