package com.tickethub.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.messaging.InvalidEventException;
import com.tickethub.common.messaging.MessagingMdc;
import com.tickethub.common.messaging.TransientProcessingException;
import com.tickethub.notification.domain.BookingState;
import com.tickethub.notification.domain.InboxEvent;
import com.tickethub.notification.domain.Notification;
import com.tickethub.notification.repo.BookingStateRepository;
import com.tickethub.notification.repo.InboxEventRepository;
import com.tickethub.notification.repo.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Turns booking events into emails, exactly once per event:
 * <ol>
 *   <li>inbox row + notification row are written in ONE transaction (unique event id rejects a second copy),</li>
 *   <li>a per-booking aggregate version rejects stale / out-of-order events,</li>
 *   <li>an atomic claim lets only one instance perform the send.</li>
 * </ol>
 * Bad data throws {@link InvalidEventException} (straight to the dead-letter topic);
 * provider trouble throws {@link TransientProcessingException} (retried on delayed retry topics).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> SUPPORTED = Set.of(BookingEvent.CONFIRMED, BookingEvent.CANCELLED, BookingEvent.REJECTED);
    private static final List<Notification.Status> CLAIMABLE =
            List.of(Notification.Status.PENDING, Notification.Status.RETRYING, Notification.Status.FAILED);

    private final NotificationRepository notifications;
    private final InboxEventRepository inbox;
    private final BookingStateRepository states;
    private final MailGateway mail;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final String from;
    private final boolean replayMode;
    private final long simulatedDelayMs;
    private final Duration claimTimeout;

    public NotificationService(NotificationRepository notifications, InboxEventRepository inbox, BookingStateRepository states,
                               MailGateway mail, ObjectMapper mapper, MeterRegistry metrics, PlatformTransactionManager tm,
                               @Value("${notification.from}") String from,
                               @Value("${notification.replay-mode:false}") boolean replayMode,
                               @Value("${notification.simulated-delay-ms:0}") long simulatedDelayMs,
                               @Value("${notification.claim-timeout-minutes:5}") long claimTimeoutMinutes) {
        this.notifications = notifications;
        this.inbox = inbox;
        this.states = states;
        this.mail = mail;
        this.mapper = mapper;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
        this.from = from;
        this.replayMode = replayMode;
        this.simulatedDelayMs = simulatedDelayMs;
        this.claimTimeout = Duration.ofMinutes(claimTimeoutMinutes);
    }

    // ------------------------------------------------------------------ entry point

    public void handle(String payload) {
        BookingEvent event = parse(payload);
        if (!SUPPORTED.contains(event.eventType())) {
            log.debug("Ignoring event type {}", event.eventType());
            return;
        }
        validate(event);
        MessagingMdc.run(null, event.bookingId(), () -> process(event));
    }

    BookingEvent parse(String payload) {
        try {
            BookingEvent event = mapper.readValue(payload, BookingEvent.class);
            if (event == null) {
                throw new InvalidEventException("Empty message");
            }
            return event;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            metrics.counter("tickethub.notification.invalid").increment();
            throw new InvalidEventException("Unreadable message: " + e.getMessage(), e);
        }
    }

    private void validate(BookingEvent e) {
        if (blank(e.eventId()) || blank(e.bookingId()) || blank(e.recipientEmail())) {
            metrics.counter("tickethub.notification.invalid").increment();
            throw new InvalidEventException("Missing eventId, bookingId or recipientEmail (event " + e.eventId() + ")");
        }
    }

    // ------------------------------------------------------------------ processing

    private void process(BookingEvent event) {
        Notification notification = register(event);
        if (notification == null) {
            return;
        }
        if (replayMode) { // rebuild the record only: a replay must never email customers again
            tx.executeWithoutResult(s -> notifications.findById(notification.getId()).ifPresent(n -> n.setStatus(Notification.Status.REPLAYED)));
            metrics.counter("tickethub.notification.replayed").increment();
            return;
        }
        deliver(notification, event);
    }

    /**
     * Inbox + state check + notification row, one transaction.
     * @return the notification to send, or null when the event is a duplicate that is already done, or stale.
     */
    private Notification register(BookingEvent event) {
        try {
            return tx.execute(s -> {
                inbox.saveAndFlush(new InboxEvent(event.eventId(), event.eventType())); // unique: second copy fails here
                BookingState state = states.findForUpdate(event.bookingId())
                        .orElseGet(() -> states.saveAndFlush(new BookingState(event.bookingId())));
                Integer version = event.aggregateVersion();
                if (version != null) {
                    if (version <= state.getLastVersion()) {
                        metrics.counter("tickethub.notification.stale").increment();
                        log.info("Ignoring stale {} (version {} <= applied {})", event.eventType(), version, state.getLastVersion());
                        return null;
                    }
                    state.setLastVersion(version);
                }
                Notification n = new Notification();
                n.setEventId(event.eventId());
                n.setBookingId(event.bookingId());
                n.setEventType(event.eventType());
                n.setRecipient(event.recipientEmail());
                n.setSubject(subject(event));
                return notifications.saveAndFlush(n);
            });
        } catch (DataIntegrityViolationException e) {
            if (inbox.existsByEventId(event.eventId())) {
                return resumeExisting(event);
            }
            throw new TransientProcessingException("Could not register event " + event.eventId(), e);
        }
    }

    /**
     * Duplicate delivery. If the first attempt completed, do nothing. If it never finished sending (crash, provider down),
     * continue it: we prefer a retry over a lost email. The atomic claim still guarantees a single sender.
     */
    private Notification resumeExisting(BookingEvent event) {
        Notification existing = notifications.findByEventId(event.eventId()).orElse(null);
        if (existing == null) {
            return null; // the first copy was stale and produced no notification
        }
        if (existing.getStatus() == Notification.Status.SENT || existing.getStatus() == Notification.Status.REPLAYED) {
            metrics.counter("tickethub.notification.duplicate").increment();
            log.info("Duplicate event {} ignored (already {})", event.eventId(), existing.getStatus());
            return null;
        }
        return existing;
    }

    private void deliver(Notification n, BookingEvent event) {
        Instant now = Instant.now();
        Integer claimed = tx.execute(s -> notifications.claim(n.getId(), Notification.Status.SENDING, CLAIMABLE, now, now.minus(claimTimeout)));
        if (claimed == null || claimed == 0) {
            log.info("Event {} is being sent by another instance or is finished; skipping", event.eventId());
            return;
        }
        try {
            if (simulatedDelayMs > 0) {
                Thread.sleep(simulatedDelayMs);
            }
            mail.send(message(event));
            mark(n.getId(), Notification.Status.SENT, null);
            metrics.counter("tickethub.notification.sent").increment();
            log.info("{} email sent for booking {}", event.eventType(), event.bookingId());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            mark(n.getId(), Notification.Status.RETRYING, "interrupted");
            throw new TransientProcessingException("interrupted while sending", ie);
        } catch (MailParseException | MailPreparationException e) {
            mark(n.getId(), Notification.Status.FAILED, e.toString());
            metrics.counter("tickethub.notification.failed").increment();
            throw new InvalidEventException("The email cannot be built or addressed: " + e.getMessage(), e);
        } catch (RuntimeException e) { // provider down, auth, circuit open, rate limited
            mark(n.getId(), Notification.Status.RETRYING, e.toString());
            metrics.counter("tickethub.notification.retrying").increment();
            log.warn("Email for booking {} could not be sent ({}); will retry", event.bookingId(), e.getClass().getSimpleName());
            throw new TransientProcessingException("Email provider unavailable: " + e.getClass().getSimpleName(), e);
        }
    }

    private void mark(Long id, Notification.Status status, String error) {
        tx.executeWithoutResult(s -> notifications.findById(id).ifPresent(n -> {
            n.setStatus(status);
            n.setAttempts(n.getAttempts() + 1);
            n.setLastError(error == null ? null : (error.length() > 490 ? error.substring(0, 490) : error));
            if (status == Notification.Status.SENT) {
                n.setSentAt(Instant.now());
            }
        }));
    }

    /** Called when retries are exhausted and the message was dead-lettered. */
    public void markFailedFromDeadLetter(String payload, String error) {
        try {
            BookingEvent event = mapper.readValue(payload, BookingEvent.class);
            if (event != null && !blank(event.eventId())) {
                tx.executeWithoutResult(s -> notifications.findByEventId(event.eventId()).ifPresent(n -> {
                    n.setStatus(Notification.Status.FAILED);
                    n.setLastError(error == null ? "dead-lettered" : (error.length() > 490 ? error.substring(0, 490) : error));
                }));
            }
        } catch (JsonProcessingException | RuntimeException ignored) {
            // an unreadable payload has no notification row to update
        }
    }

    // ------------------------------------------------------------------ message content

    private String subject(BookingEvent e) {
        return switch (e.eventType()) {
            case BookingEvent.CANCELLED -> "Booking cancelled: " + e.eventTitle();
            case BookingEvent.REJECTED -> "Booking could not be confirmed: " + e.eventTitle();
            default -> "Booking confirmed: " + e.eventTitle();
        };
    }

    private SimpleMailMessage message(BookingEvent e) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(e.recipientEmail());
        msg.setSubject(subject(e));
        String seats = e.seats() == null ? "-" : String.join(", ", e.seats());
        String amount = e.effectiveAmount() + " " + e.currency();
        StringBuilder body = new StringBuilder();
        switch (e.eventType()) {
            case BookingEvent.CANCELLED -> body.append("Your booking has been cancelled and a refund of ").append(amount)
                    .append(" is being processed.\n\n");
            case BookingEvent.REJECTED -> body.append("We could not confirm your booking (").append(e.reason() == null ? "unavailable" : e.reason())
                    .append("). A refund of ").append(amount).append(" is being processed.\n\n");
            default -> body.append("Your booking is confirmed.\n\n");
        }
        body.append("Booking ID : ").append(e.bookingId()).append("\n")
                .append("Event      : ").append(e.eventTitle()).append("\n")
                .append("Show time  : ").append(e.showStartTime()).append("\n")
                .append("Seats      : ").append(seats).append("\n");
        if (e.seatCategory() != null) {
            body.append("Category   : ").append(e.seatCategory()).append("\n");
        }
        body.append("Amount     : ").append(amount).append("\n\nThank you for booking with TicketHub.");
        msg.setText(body.toString());
        return msg;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
