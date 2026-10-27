package com.tickethub.booking.repo;

import com.tickethub.booking.domain.SagaLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SagaLogRepository extends JpaRepository<SagaLogEntry, Long> {
    List<SagaLogEntry> findByBookingIdOrderByIdAsc(String bookingId);
}
