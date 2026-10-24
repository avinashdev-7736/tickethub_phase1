package com.tickethub.event.repo;

import com.tickethub.event.domain.Event;
import com.tickethub.event.domain.Show;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long> {

    boolean existsByVenueId(Long venueId);

    List<Show> findByEventId(Long eventId);

    @Query(value = "select s from Show s where s.event.status = :published and s.status in :statuses "
            + "and s.startTime > :now and (:eventId is null or s.event.id = :eventId) "
            + "and (:fromTime is null or s.startTime >= :fromTime) and (:toTime is null or s.startTime < :toTime)",
            countQuery = "select count(s) from Show s where s.event.status = :published and s.status in :statuses "
                    + "and s.startTime > :now and (:eventId is null or s.event.id = :eventId) "
                    + "and (:fromTime is null or s.startTime >= :fromTime) and (:toTime is null or s.startTime < :toTime)")
    Page<Show> findVisible(@Param("published") Event.Status published,
                           @Param("statuses") Collection<Show.Status> statuses,
                           @Param("now") Instant now,
                           @Param("eventId") Long eventId,
                           @Param("fromTime") Instant fromTime,
                           @Param("toTime") Instant toTime,
                           Pageable pageable);

    @Query("select s from Show s join fetch s.event join fetch s.venue where s.id = :id")
    java.util.Optional<Show> findDetailedById(@Param("id") Long id);
}
