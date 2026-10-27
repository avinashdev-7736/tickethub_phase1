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
                   @Index(name = "idx_payment_status_updated", columnList = "status,updated_at"),
                   @Index(name = "idx_payment_customer", columnList = "customer_id")})
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    /**
     * Forward-only. A transition is applied only if the new status has a HIGHER rank than the current one, so duplicated,
     * late or out-of-order provider callbacks can never move a payment backwards.
     * UNKNOWN = we asked the provider and got no answer (timeout): we do not know yet whether money moved.
     */
    public enum Status {
        INITIATED(0), PENDING(1), UNKNOWN(1), AUTHORIZED(2), FAILED(3), SUCCESS(4), REFUNDING(5), REFUNDED(6), CANCELLED(3);

        private final int rank;

        Status(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

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

    /** Provider order / reference created at initiation (Razorpay order id). */
    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    /** Provider payment id learned from the callback; needed to refund (Razorpay pay_xxx). */
    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "refund_id", length = 100)
    private String refundId;

    /** Version of the events published about this payment; goes up by one with every event. */
    @Column(name = "event_seq")
    private Integer eventSeq = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public int nextEventSeq() {
        this.eventSeq = (eventSeq == null ? 0 : eventSeq) + 1;
        return this.eventSeq;
    }

    public int currentEventSeq() {
        return eventSeq == null ? 0 : eventSeq;
    }
}
