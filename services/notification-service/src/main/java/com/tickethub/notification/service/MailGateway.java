package com.tickethub.notification.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * The only place that talks to the mail provider. A circuit breaker stops hammering a provider that is down,
 * and a rate limiter keeps us under its send limits. Both reject calls with an exception that the caller treats as transient.
 */
@Component
public class MailGateway {

    private final JavaMailSender sender;

    public MailGateway(JavaMailSender sender) {
        this.sender = sender;
    }

    @CircuitBreaker(name = "mail")
    @RateLimiter(name = "mail")
    public void send(SimpleMailMessage message) {
        sender.send(message);
    }
}
