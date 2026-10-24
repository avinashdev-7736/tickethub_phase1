package com.tickethub.event.repo;

import com.tickethub.event.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("select e from Event e where e.status = :status "
            + "and (:category is null or e.category = :category) "
            + "and (:q is null or lower(e.title) like lower(concat('%', :q, '%')))")
    Page<Event> search(@Param("status") Event.Status status, @Param("category") String category,
                       @Param("q") String q, Pageable pageable);
}
