package com.tickethub.booking.service;

import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.repo.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Expires unpaid bookings and releases their seats. Safe with several instances (row lock + re-check). */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final BookingRepository bookings;
    private final BookingService service;

    public ExpiryJob(BookingRepository bookings, BookingService service) {
        this.bookings = bookings;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${booking.scan-interval-ms:30000}", initialDelayString = "${booking.scan-interval-ms:30000}")
    public void run() {
        try {
            List<String> ids;
            do {
                ids = bookings.findExpiredIds(Booking.Status.PENDING_PAYMENT, Instant.now(), PageRequest.of(0, 100));
                int expired = 0;
                for (String id : ids) {
                    if (service.expireOne(id)) {
                        expired++;
                    }
                }
                if (expired == 0) {
                    break; // nothing progressed (e.g. other instance won the race); avoid spinning
                }
            } while (!ids.isEmpty());
        } catch (RuntimeException e) {
            log.error("Booking expiry scan failed; will retry on the next run", e);
        }
    }
}
