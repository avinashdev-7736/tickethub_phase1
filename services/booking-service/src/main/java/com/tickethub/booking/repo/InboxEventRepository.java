package com.tickethub.booking.repo;

import com.tickethub.booking.domain.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {
    boolean existsByEventId(String eventId);
}
