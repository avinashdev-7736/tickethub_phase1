package com.tickethub.payment.repo;

import com.tickethub.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByBookingId(String bookingId);

    Optional<Payment> findByProviderReference(String providerReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.bookingId = :bookingId")
    Optional<Payment> findForUpdateByBookingId(@Param("bookingId") String bookingId);

    @Query("select p from Payment p where p.status in :statuses and p.updatedAt < :before order by p.updatedAt asc")
    List<Payment> findStale(@Param("statuses") Collection<Payment.Status> statuses, @Param("before") Instant before);
}
