package com.tickethub.notification.repo;

import com.tickethub.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByEventId(String eventId);

    /**
     * Atomic claim: exactly one caller moves the row to SENDING, so two instances never send the same email.
     * A SENDING row older than {@code staleBefore} belongs to a crashed instance and may be taken over.
     * @return 1 if this caller now owns the send, 0 otherwise
     */
    @Modifying
    @Query("update Notification n set n.status = :sending, n.claimedAt = :now "
            + "where n.id = :id and (n.status in :claimable or (n.status = :sending and n.claimedAt < :staleBefore))")
    int claim(@Param("id") Long id, @Param("sending") Notification.Status sending,
              @Param("claimable") Collection<Notification.Status> claimable,
              @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
