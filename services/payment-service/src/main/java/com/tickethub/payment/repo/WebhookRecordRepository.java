package com.tickethub.payment.repo;

import com.tickethub.payment.domain.WebhookRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRecordRepository extends JpaRepository<WebhookRecord, Long> {
}
