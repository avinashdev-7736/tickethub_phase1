package com.tickethub.booking.repo;

import com.tickethub.booking.domain.SeatReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatReservationRepository extends JpaRepository<SeatReservation, Long> {

    List<SeatReservation> findByShowId(Long showId);

    @Modifying
    @Query("delete from SeatReservation r where r.bookingId = :bookingId")
    int deleteByBookingId(@Param("bookingId") String bookingId);

    @Modifying
    @Query("update SeatReservation r set r.state = :state where r.bookingId = :bookingId")
    int updateState(@Param("bookingId") String bookingId, @Param("state") SeatReservation.State state);
}
