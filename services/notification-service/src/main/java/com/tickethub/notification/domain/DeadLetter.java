package com.tickethub.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A message that exhausted its retries (or was never retryable). Kept so it can be inspected and sent back after a fix. */
@Entity
@Table(name = "dead_letter")
@Getter
@Setter
@NoArgsConstructor
public class DeadLetter {

    public enum Status { NEW, REPLAYED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_topic", length = 200)
    private String sourceTopic;

    @Column(name = "message_key", length = 100)
    private String messageKey;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(length = 1000)
    private String error;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.NEW;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "replayed_at")
    private Instant replayedAt;
}
