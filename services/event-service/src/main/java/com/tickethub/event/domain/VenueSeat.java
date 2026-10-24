package com.tickethub.event.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "venue_seat",
        uniqueConstraints = @UniqueConstraint(name = "uk_venue_seat", columnNames = {"venue_id", "row_label", "seat_number"}))
@Getter
@Setter
@NoArgsConstructor
public class VenueSeat {

    public enum SeatType { STANDARD, PREMIUM, VIP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "row_label", nullable = false, length = 5)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatType seatType = SeatType.STANDARD;

    public String label() {
        return rowLabel + seatNumber;
    }
}
