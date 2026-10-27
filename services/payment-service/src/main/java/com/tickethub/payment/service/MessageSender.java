package com.tickethub.payment.service;

/** Port to the broker. Tests replace it to simulate a Kafka outage. */
public interface MessageSender {
    void send(String topic, String key, String payload, String correlationId) throws Exception;
}
