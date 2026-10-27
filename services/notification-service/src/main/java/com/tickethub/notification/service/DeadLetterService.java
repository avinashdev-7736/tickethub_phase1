package com.tickethub.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.BusinessException;
import com.tickethub.notification.domain.DeadLetter;
import com.tickethub.notification.repo.DeadLetterRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Dead letters: record them, list them, and send one back for processing after the data or the code was fixed. */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository repo;
    private final NotificationService notifications;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final String topic;

    public DeadLetterService(DeadLetterRepository repo, NotificationService notifications, KafkaTemplate<String, String> kafka,
                             ObjectMapper mapper, MeterRegistry metrics,
                             @Value("${tickethub.kafka.topic.booking-events}") String topic) {
        this.repo = repo;
        this.notifications = notifications;
        this.kafka = kafka;
        this.mapper = mapper;
        this.metrics = metrics;
        this.topic = topic;
    }

    public DeadLetter record(String payload, String sourceTopic, String error) {
        DeadLetter d = new DeadLetter();
        d.setPayload(payload);
        d.setSourceTopic(sourceTopic);
        d.setMessageKey(keyOf(payload));
        d.setError(error == null ? null : (error.length() > 990 ? error.substring(0, 990) : error));
        DeadLetter saved = repo.save(d);
        notifications.markFailedFromDeadLetter(payload, error);
        metrics.counter("tickethub.notification.deadlettered").increment();
        log.error("Message dead-lettered (id {}): {}", saved.getId(), error);
        return saved;
    }

    public List<DeadLetter> list() {
        return repo.findTop100ByOrderByIdDesc();
    }

    /** Publish the (optionally corrected) payload back to the main topic. The inbox keeps it from being applied twice. */
    public DeadLetter replay(Long id, String correctedPayload) {
        DeadLetter d = repo.findById(id).orElseThrow(() -> BusinessException.notFound("DEAD_LETTER_NOT_FOUND", "Dead letter not found"));
        String payload = (correctedPayload != null && !correctedPayload.isBlank()) ? correctedPayload : d.getPayload();
        try {
            kafka.send(topic, keyOf(payload), payload).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw BusinessException.unavailable("REPLAY_FAILED", "Could not publish the message again: " + e.getMessage());
        }
        d.setStatus(DeadLetter.Status.REPLAYED);
        d.setReplayedAt(Instant.now());
        return repo.save(d);
    }

    private String keyOf(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            JsonNode booking = node == null ? null : node.get("bookingId");
            return booking == null || booking.isNull() ? null : booking.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
