package com.tickethub.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.notification.domain.Notification;
import com.tickethub.notification.repo.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Handles BookingConfirmed. This method never throws: a notification problem must not affect bookings,
 * and an exception would only make the Kafka listener re-deliver the same message repeatedly.
 * Phase 2 adds retry topics / DLT / replay for FAILED notifications.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final JavaMailSender mailSender;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final String from;

    public NotificationService(NotificationRepository repo, JavaMailSender mailSender, ObjectMapper mapper,
                               MeterRegistry metrics, PlatformTransactionManager tm,
                               @Value("${notification.from}") String from) {
        this.repo = repo;
        this.mailSender = mailSender;
        this.mapper = mapper;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
        this.from = from;
    }

    public void handle(String payload) {
        BookingConfirmedEvent event;
        try {
            event = mapper.readValue(payload, BookingConfirmedEvent.class);
        } catch (Exception e) {
            metrics.counter("tickethub.notification.invalid").increment();
            log.error("Dropping unreadable BookingConfirmed message: {}", e.toString());
            return;
        }
        if (blank(event.eventId()) || blank(event.bookingId()) || blank(event.recipientEmail())) {
            metrics.counter("tickethub.notification.invalid").increment();
            log.error("Dropping BookingConfirmed with missing eventId/bookingId/recipientEmail");
            return;
        }
        MDC.put("bookingId", event.bookingId());
        try {
            process(event);
        } catch (RuntimeException e) {
            metrics.counter("tickethub.notification.error").increment();
            log.error("Unexpected error while handling event {}: {}", event.eventId(), e.toString());
        } finally {
            MDC.remove("bookingId");
        }
    }

    private void process(BookingConfirmedEvent event) {
        Notification n;
        try {
            n = tx.execute(s -> {
                Notification created = new Notification();
                created.setEventId(event.eventId());
                created.setBookingId(event.bookingId());
                created.setRecipient(event.recipientEmail());
                created.setSubject("Booking confirmed: " + event.eventTitle());
                return repo.saveAndFlush(created);
            });
        } catch (DataIntegrityViolationException duplicate) {
            Notification existing = repo.findByEventId(event.eventId()).orElse(null);
            if (existing == null || existing.getStatus() != Notification.Status.PENDING) {
                metrics.counter("tickethub.notification.duplicate").increment();
                log.info("Duplicate event {} ignored", event.eventId());
                return;
            }
            n = existing; // earlier attempt crashed before finishing: complete it
        }
        send(n.getId(), event);
    }

    private void send(Long notificationId, BookingConfirmedEvent e) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(e.recipientEmail());
            msg.setSubject("Booking confirmed: " + e.eventTitle());
            msg.setText(body(e));
            mailSender.send(msg);
            update(notificationId, Notification.Status.SENT, null);
            metrics.counter("tickethub.notification.sent").increment();
            log.info("Confirmation email sent for booking {}", e.bookingId());
        } catch (Exception ex) {
            update(notificationId, Notification.Status.FAILED, ex.toString());
            metrics.counter("tickethub.notification.failed").increment();
            log.warn("Email failed for booking {} (booking is unaffected): {}", e.bookingId(), ex.toString());
        }
    }

    private void update(Long id, Notification.Status status, String error) {
        tx.executeWithoutResult(s -> repo.findById(id).ifPresent(n -> {
            n.setStatus(status);
            n.setAttempts(n.getAttempts() + 1);
            n.setLastError(error == null ? null : (error.length() > 490 ? error.substring(0, 490) : error));
            if (status == Notification.Status.SENT) {
                n.setSentAt(Instant.now());
            }
        }));
    }

    private String body(BookingConfirmedEvent e) {
        return "Your booking is confirmed.\n\n"
                + "Booking ID : " + e.bookingId() + "\n"
                + "Event      : " + e.eventTitle() + "\n"
                + "Show time  : " + e.showStartTime() + "\n"
                + "Seats      : " + (e.seats() == null ? "-" : String.join(", ", e.seats())) + "\n"
                + "Amount     : " + e.amount() + " " + e.currency() + "\n\n"
                + "Thank you for booking with TicketHub.";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
