package com.tickethub.booking.repo;

import com.tickethub.booking.domain.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    /** Row lock so cancel / payment-result / expiry on the same booking are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findForUpdate(@Param("id") String id);

    @Query("select b.id from Booking b where b.status = :status and b.expiresAt < :now")
    List<String> findExpiredIds(@Param("status") Booking.Status status, @Param("now") Instant now, Pageable pageable);
}
