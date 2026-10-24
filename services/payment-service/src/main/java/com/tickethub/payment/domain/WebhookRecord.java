package com.tickethub.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One row per processed provider event id: the UNIQUE key makes duplicate webhooks harmless. */
@Entity
@Table(name = "webhook_record", uniqueConstraints = @UniqueConstraint(name = "uk_webhook_event", columnNames = "event_id"))
@Getter
@Setter
@NoArgsConstructor
public class WebhookRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 150)
    private String eventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    public WebhookRecord(String eventId) {
        this.eventId = eventId;
    }
}
