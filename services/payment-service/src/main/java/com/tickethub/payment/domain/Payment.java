package com.tickethub.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment",
        uniqueConstraints = {@UniqueConstraint(name = "uk_payment_booking", columnNames = "booking_id")},
        indexes = {@Index(name = "idx_payment_provider_ref", columnList = "provider_reference"),
                   @Index(name = "idx_payment_customer", columnList = "customer_id")})
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    public enum Status { INITIATED, PENDING, SUCCESS, FAILED, CANCELLED, REFUNDED }

    /** Did Booking Service learn the outcome? Anything other than SYNCED is picked up by Phase 2 reconciliation. */
    public enum BookingSync { NOT_REQUIRED, SYNCED, FAILED_TO_SYNC, REJECTED_NEEDS_REFUND }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    /** Always copied from the booking, never from the client. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.INITIATED;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_sync", nullable = false, length = 30)
    private BookingSync bookingSync = BookingSync.NOT_REQUIRED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return status == Status.SUCCESS || status == Status.FAILED;
    }
}
