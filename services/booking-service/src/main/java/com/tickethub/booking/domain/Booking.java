package com.tickethub.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "booking",
        uniqueConstraints = @UniqueConstraint(name = "uk_booking_idem", columnNames = {"customer_id", "idempotency_key"}),
        indexes = {@Index(name = "idx_booking_status_exp", columnList = "status,expires_at"),
                   @Index(name = "idx_booking_customer", columnList = "customer_id")})
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    /**
     * PENDING_PAYMENT -> CONFIRMED | PAYMENT_FAILED | CANCELLED | EXPIRED
     * Paid after the hold lapsed (or after cancel):  -> REJECTED (money is refunded)
     * Customer cancels a CONFIRMED booking:          -> CANCELLING -> CANCELLED once the refund is done
     */
    public enum Status { PENDING_PAYMENT, CONFIRMED, PAYMENT_FAILED, CANCELLED, EXPIRED, REJECTED, CANCELLING }

    public enum RefundState { NONE, PENDING, REFUNDED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "customer_email", nullable = false, length = 200)
    private String customerEmail;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "event_title", nullable = false, length = 150)
    private String eventTitle;

    @Column(name = "show_start_time", nullable = false)
    private Instant showStartTime;

    /** Snapshot taken at booking time: later price changes never alter an existing booking. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Highest seat category booked (STANDARD / PREMIUM / VIP): added in event contract v2. */
    @Column(name = "seat_category", length = 20)
    private String seatCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING_PAYMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_state", length = 10)
    private RefundState refundState = RefundState.NONE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /** Version of the events THIS service publishes about the booking; goes up by one with every published event. */
    @Column(name = "event_seq")
    private Integer eventSeq = 0;

    /** Highest payment-event version already applied: an older one arriving later is stale. */
    @Column(name = "last_payment_seq")
    private Integer lastPaymentSeq = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingSeat> seats = new ArrayList<>();

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

    public int currentPaymentSeq() {
        return lastPaymentSeq == null ? 0 : lastPaymentSeq;
    }

    public RefundState refundOrNone() {
        return refundState == null ? RefundState.NONE : refundState;
    }
}
