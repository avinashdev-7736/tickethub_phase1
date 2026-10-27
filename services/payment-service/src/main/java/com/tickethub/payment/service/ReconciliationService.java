package com.tickethub.payment.service;

import com.tickethub.common.error.BusinessException;
import com.tickethub.common.messaging.TransientProcessingException;
import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.domain.ReconciliationFinding;
import com.tickethub.payment.dto.Dtos.FindingView;
import com.tickethub.payment.dto.Dtos.InternalBooking;
import com.tickethub.payment.dto.Dtos.ReconciliationReport;
import com.tickethub.payment.provider.PaymentProvider;
import com.tickethub.payment.repo.PaymentRepository;
import com.tickethub.payment.repo.ReconciliationFindingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation: compare the three sources of truth (our payments, the provider, and Booking) and repair what is safe to repair.
 * Rule: fix only when the provider says what happened; anything involving money we cannot prove is FLAGGED for a human, never guessed.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final List<Payment.Status> UNRESOLVED =
            List.of(Payment.Status.INITIATED, Payment.Status.PENDING, Payment.Status.UNKNOWN, Payment.Status.AUTHORIZED);

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final RefundService refunds;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final BookingClient bookingClient;
    private final ReconciliationFindingRepository findingsRepo;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final Duration minAge;
    private final Duration lookback;

    public ReconciliationService(PaymentRepository payments, PaymentService paymentService, RefundService refunds,
                                 PaymentProvider provider, PaymentEvents events, BookingClient bookingClient,
                                 ReconciliationFindingRepository findingsRepo, PlatformTransactionManager tm, MeterRegistry metrics,
                                 @Value("${payment.reconcile.min-age-seconds:120}") long minAgeSeconds,
                                 @Value("${payment.reconcile.lookback-hours:24}") long lookbackHours) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.refunds = refunds;
        this.provider = provider;
        this.events = events;
        this.bookingClient = bookingClient;
        this.findingsRepo = findingsRepo;
        this.tx = new TransactionTemplate(tm);
        this.metrics = metrics;
        this.minAge = Duration.ofSeconds(minAgeSeconds);
        this.lookback = Duration.ofHours(lookbackHours);
    }

    @Scheduled(fixedDelayString = "${payment.reconcile.interval-ms:300000}", initialDelayString = "${payment.reconcile.interval-ms:300000}")
    public void scheduled() {
        try {
            ReconciliationReport report = run();
            if (report.fixed() + report.flagged() > 0) {
                log.warn("Reconciliation {}: {} fixed, {} flagged", report.runId(), report.fixed(), report.flagged());
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation run failed; will retry on the next tick", e);
        }
    }

    public ReconciliationReport run() {
        String runId = UUID.randomUUID().toString();
        List<ReconciliationFinding> findings = new ArrayList<>();
        Instant cutoff = Instant.now().minus(minAge);

        for (Payment p : payments.findStale(UNRESOLVED, cutoff)) {
            unresolved(runId, p, findings);
        }
        for (Payment p : payments.findStale(List.of(Payment.Status.SUCCESS), cutoff)) {
            succeededButBookingDisagrees(runId, p, findings);
        }
        for (Payment p : payments.findStale(List.of(Payment.Status.REFUNDING), cutoff)) {
            findings.add(finding(runId, p, "REFUND_STUCK", ReconciliationFinding.Action.FLAGGED,
                    "Refund started but never completed: check with the provider before retrying, to avoid refunding twice"));
        }
        confirmedWithoutPayment(runId, findings);

        findingsRepo.saveAll(findings);
        int fixed = (int) findings.stream().filter(f -> f.getAction() == ReconciliationFinding.Action.FIXED).count();
        metrics.counter("tickethub.reconciliation.fixed").increment(fixed);
        metrics.counter("tickethub.reconciliation.flagged").increment(findings.size() - fixed);
        List<FindingView> views = findings.stream()
                .map(f -> new FindingView(f.getKind(), f.getAction().name(), f.getPaymentId(), f.getBookingId(), f.getDetail())).toList();
        return new ReconciliationReport(runId, Instant.now(), fixed, findings.size() - fixed, views);
    }

    public List<FindingView> recent() {
        return findingsRepo.findTop100ByOrderByIdDesc().stream()
                .map(f -> new FindingView(f.getKind(), f.getAction().name(), f.getPaymentId(), f.getBookingId(), f.getDetail())).toList();
    }

    // ---- payments we never got a final answer for: ask the provider

    private void unresolved(String runId, Payment p, List<ReconciliationFinding> out) {
        if (p.getProviderReference() == null) {
            paymentService.apply(p.getId(), PaymentProvider.Status.FAILED, null, null, "NO_PROVIDER_REFERENCE");
            out.add(finding(runId, p, "UNRESOLVED_NO_REFERENCE", ReconciliationFinding.Action.FIXED,
                    "Payment never reached the provider (no reference): marked FAILED"));
            return;
        }
        PaymentProvider.Result truth = provider.fetch(p.getProviderReference());
        switch (truth.status()) {
            case SUCCESS, FAILED -> {
                paymentService.apply(p.getId(), truth.status(), null, null, truth.reason());
                out.add(finding(runId, p, "UNRESOLVED_RESOLVED", ReconciliationFinding.Action.FIXED,
                        "Provider says " + truth.status() + " (callback was lost or the call timed out): payment updated"));
            }
            case AUTHORIZED -> {
                paymentService.apply(p.getId(), PaymentProvider.Status.AUTHORIZED, null, null, null);
                out.add(finding(runId, p, "AUTHORIZED_NOT_CAPTURED", ReconciliationFinding.Action.FLAGGED,
                        "Provider authorized the payment but has not captured it"));
            }
            default -> out.add(finding(runId, p, "UNRESOLVED_AT_PROVIDER", ReconciliationFinding.Action.FLAGGED,
                    "Provider still reports " + truth.status() + " for " + p.getProviderReference()));
        }
    }

    // ---- money captured: does the booking agree?

    private void succeededButBookingDisagrees(String runId, Payment p, List<ReconciliationFinding> out) {
        InternalBooking booking;
        try {
            booking = bookingClient.getBooking(p.getBookingId());
        } catch (BusinessException e) {
            String kind = "BOOKING_NOT_FOUND".equals(e.getCode()) ? "PAYMENT_WITHOUT_BOOKING" : "BOOKING_UNREACHABLE";
            out.add(finding(runId, p, kind, ReconciliationFinding.Action.FLAGGED, e.getMessage()));
            return;
        }
        switch (booking.status()) {
            case "CONFIRMED" -> { /* consistent */ }
            case "PENDING_PAYMENT" -> {
                tx.executeWithoutResult(s -> payments.findForUpdate(p.getId()).ifPresent(events::succeededAgain));
                out.add(finding(runId, p, "PAYMENT_NOT_APPLIED", ReconciliationFinding.Action.FIXED,
                        "Booking never learned about the payment: PaymentSucceeded published again"));
            }
            default -> { // REJECTED, CANCELLING, CANCELLED, EXPIRED, PAYMENT_FAILED: the customer paid for a booking that cannot exist
                try {
                    String refundEventId = UUID.nameUUIDFromBytes(
                            ("recon-" + runId + "-" + p.getId()).getBytes(StandardCharsets.UTF_8)).toString();
                    boolean done = refunds.refundFor(p.getBookingId(), refundEventId, "RECONCILIATION");
                    out.add(finding(runId, p, "PAID_BUT_BOOKING_" + booking.status(),
                            done ? ReconciliationFinding.Action.FIXED : ReconciliationFinding.Action.FLAGGED,
                            done ? "Refund issued by reconciliation" : "Refund already in progress or not possible"));
                } catch (TransientProcessingException e) {
                    out.add(finding(runId, p, "PAID_BUT_BOOKING_" + booking.status(), ReconciliationFinding.Action.FLAGGED,
                            "Refund attempt failed: " + e.getMessage()));
                }
            }
        }
    }

    // ---- the other direction: bookings confirmed with no money behind them

    private void confirmedWithoutPayment(String runId, List<ReconciliationFinding> out) {
        List<InternalBooking> confirmed;
        try {
            confirmed = bookingClient.confirmedSince(Instant.now().minus(lookback));
        } catch (RuntimeException e) {
            ReconciliationFinding f = new ReconciliationFinding();
            f.setRunId(runId);
            f.setKind("BOOKING_LIST_UNAVAILABLE");
            f.setAction(ReconciliationFinding.Action.FLAGGED);
            f.setDetail("Could not read confirmed bookings: " + e.getMessage());
            out.add(f);
            return;
        }
        Instant recent = Instant.now().minus(minAge);
        for (InternalBooking b : confirmed) {
            Payment p = payments.findByBookingId(b.id()).orElse(null);
            boolean paid = p != null && p.getStatus().rank() >= Payment.Status.SUCCESS.rank();
            if (!paid && (p == null || p.getUpdatedAt().isBefore(recent))) {
                out.add(finding(runId, p, b.id(), "CONFIRMED_WITHOUT_PAYMENT", ReconciliationFinding.Action.FLAGGED,
                        "Booking is CONFIRMED but no captured payment exists: investigate before the show"));
            }
        }
    }

    private ReconciliationFinding finding(String runId, Payment p, String kind, ReconciliationFinding.Action action, String detail) {
        return finding(runId, p, p.getBookingId(), kind, action, detail);
    }

    private ReconciliationFinding finding(String runId, Payment p, String bookingId, String kind,
                                          ReconciliationFinding.Action action, String detail) {
        ReconciliationFinding f = new ReconciliationFinding();
        f.setRunId(runId);
        f.setPaymentId(p == null ? null : p.getId());
        f.setBookingId(bookingId);
        f.setKind(kind);
        f.setAction(action);
        f.setDetail(detail == null ? null : (detail.length() > 490 ? detail.substring(0, 490) : detail));
        return f;
    }
}
