package com.tickethub.payment.repo;

import com.tickethub.payment.domain.ReconciliationFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, Long> {
    List<ReconciliationFinding> findTop100ByOrderByIdDesc();
}
