package com.tickethub.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Something reconciliation found: either fixed automatically, or flagged for a human (we never guess about money). */
@Entity
@Table(name = "reconciliation_finding", indexes = @Index(name = "idx_recon_run", columnList = "run_id"))
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationFinding {

    public enum Action { FIXED, FLAGGED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(nullable = false, length = 60)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Action action;

    @Column(length = 500)
    private String detail;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();
}
