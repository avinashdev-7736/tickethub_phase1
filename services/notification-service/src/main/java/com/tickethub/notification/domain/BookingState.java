package com.tickethub.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Highest aggregate version already applied per booking: a lower version arriving later is stale and ignored. */
@Entity
@Table(name = "booking_state")
@Getter
@Setter
@NoArgsConstructor
public class BookingState {

    @Id
    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "last_version", nullable = false)
    private int lastVersion = 0;

    public BookingState(String bookingId) {
        this.bookingId = bookingId;
    }
}
