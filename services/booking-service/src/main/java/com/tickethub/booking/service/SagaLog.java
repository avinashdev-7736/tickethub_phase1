package com.tickethub.booking.service;

import com.tickethub.booking.domain.SagaLogEntry;
import com.tickethub.booking.repo.SagaLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SagaLog {

    private final SagaLogRepository repo;

    public SagaLog(SagaLogRepository repo) {
        this.repo = repo;
    }

    /** Joins the caller's transaction so the log entry and the state change commit (or roll back) together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String bookingId, String from, String to, String reason, String eventId) {
        SagaLogEntry e = new SagaLogEntry();
        e.setBookingId(bookingId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setReason(reason == null ? null : (reason.length() > 290 ? reason.substring(0, 290) : reason));
        e.setEventId(eventId);
        repo.save(e);
    }
}
