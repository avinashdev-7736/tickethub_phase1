package com.tickethub.notification.repo;

import com.tickethub.notification.domain.DeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    List<DeadLetter> findTop100ByOrderByIdDesc();
}
