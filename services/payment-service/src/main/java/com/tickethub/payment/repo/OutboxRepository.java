package com.tickethub.payment.repo;

import com.tickethub.payment.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select o.id from OutboxEvent o where o.publishedAt is null order by o.id asc")
    List<Long> findUnpublishedIds(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxEvent o where o.id = :id")
    Optional<OutboxEvent> findForUpdate(@Param("id") Long id);

    long countByPublishedAtIsNull();

    List<OutboxEvent> findByMessageKeyOrderByIdAsc(String messageKey);
}
