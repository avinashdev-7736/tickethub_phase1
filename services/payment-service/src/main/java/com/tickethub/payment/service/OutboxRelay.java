package com.tickethub.payment.service;

import com.tickethub.payment.domain.OutboxEvent;
import com.tickethub.payment.repo.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Publishes outbox rows to Kafka in the order they were written. If the broker is down the row stays unpublished and the next
 * tick retries; order is preserved by stopping at the first failure. A row lock makes it safe to run on several instances.
 * Delivery is at-least-once, so consumers de-duplicate with their inbox.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final MessageSender sender;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public OutboxRelay(OutboxRepository outbox, MessageSender sender, MeterRegistry metrics, PlatformTransactionManager tm) {
        this.outbox = outbox;
        this.sender = sender;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
        Gauge.builder("tickethub.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events written but not yet published to Kafka").register(metrics);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:1000}")
    public void scheduled() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.error("Outbox relay run failed; will retry on the next tick", e);
        }
    }

    /** @return number of events published in this run */
    public int relayOnce() {
        List<Long> ids = outbox.findUnpublishedIds(PageRequest.of(0, 50));
        int published = 0;
        for (Long id : ids) {
            Boolean ok = tx.execute(s -> {
                OutboxEvent e = outbox.findForUpdate(id).orElse(null);
                if (e == null || e.getPublishedAt() != null) {
                    return true; // another instance already did it
                }
                try {
                    sender.send(e.getTopic(), e.getMessageKey(), e.getPayload(), e.getCorrelationId());
                    e.setPublishedAt(Instant.now());
                    e.setAttempts(e.getAttempts() + 1);
                    e.setLastError(null);
                    metrics.counter("tickethub.outbox.published").increment();
                    return true;
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    e.setAttempts(e.getAttempts() + 1);
                    String msg = ex.toString();
                    e.setLastError(msg.length() > 490 ? msg.substring(0, 490) : msg);
                    metrics.counter("tickethub.outbox.publish.failed").increment();
                    log.warn("Outbox event {} ({}) not published yet: {}", e.getEventId(), e.getEventType(), ex.toString());
                    return false;
                }
            });
            if (!Boolean.TRUE.equals(ok)) {
                break; // keep order: do not publish later events before this one
            }
            published++;
        }
        return published;
    }
}
