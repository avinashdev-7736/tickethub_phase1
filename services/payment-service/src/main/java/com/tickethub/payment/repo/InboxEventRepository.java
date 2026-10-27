package com.tickethub.payment.repo;

import com.tickethub.payment.domain.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {
    boolean existsByEventId(String eventId);
}
