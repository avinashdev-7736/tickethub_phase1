package com.tickethub.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.payment.domain.OutboxEvent;
import com.tickethub.payment.repo.OutboxRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Writes events into the outbox. MANDATORY: it must run inside the business transaction, never on its own. */
@Service
public class OutboxService {

    public static final int SCHEMA_VERSION = 2;

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final String topic;

    public OutboxService(OutboxRepository outbox, ObjectMapper mapper,
                         @Value("${tickethub.kafka.topic.payment-events}") String topic) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent add(String eventType, String bookingId, int aggregateVersion, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        String correlationId = MDC.get("correlationId");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("bookingId", bookingId);
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("correlationId", correlationId);
        envelope.putAll(payload);
        OutboxEvent row = new OutboxEvent();
        row.setEventId(eventId);
        row.setTopic(topic);
        row.setMessageKey(bookingId); // key = booking id: all events of one booking go to one partition, in order
        row.setEventType(eventType);
        row.setCorrelationId(correlationId);
        try {
            row.setPayload(mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event " + eventType, e);
        }
        return outbox.save(row);
    }
}
