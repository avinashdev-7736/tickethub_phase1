package com.tickethub.notification.repo;

import com.tickethub.notification.domain.BookingState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingStateRepository extends JpaRepository<BookingState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BookingState s where s.bookingId = :id")
    Optional<BookingState> findForUpdate(@Param("id") String id);
}
