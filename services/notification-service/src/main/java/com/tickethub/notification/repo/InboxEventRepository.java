package com.tickethub.notification.repo;

import com.tickethub.notification.domain.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {
    boolean existsByEventId(String eventId);
}
