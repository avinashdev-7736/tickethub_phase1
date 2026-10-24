package com.tickethub.payment;

import com.tickethub.common.error.BusinessException;
import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.dto.Dtos.InternalBooking;
import com.tickethub.payment.dto.Dtos.PaymentResponse;
import com.tickethub.payment.provider.SimulatedPaymentProvider;
import com.tickethub.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paymenttest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "eureka.client.enabled=false",
        "payment.webhook.secret=test-secret"
})
class PaymentFlowTest {

    @Autowired PaymentService service;
    @Autowired SimulatedPaymentProvider provider;
    @MockBean BookingClient bookingClient;

    private InternalBooking booking(String id, String customer, String status, Instant expires) {
        InternalBooking b = new InternalBooking(id, customer, status, new BigDecimal("250.00"), "INR", expires, 1L);
        when(bookingClient.getBooking(id)).thenReturn(b);
        return b;
    }

    @BeforeEach
    void reset() {
        provider.configure(SimulatedPaymentProvider.Mode.SUCCESS, 0);
    }

    @Test
    void successfulPaymentUsesBookingAmountAndReportsToBooking() {
        booking("b1", "u1", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
        PaymentResponse r = service.initiate("b1", "u1");
        assertEquals("SUCCESS", r.status());
        assertEquals(0, new BigDecimal("250.00").compareTo(r.amount()));
        assertEquals("SYNCED", r.bookingSync());
        verify(bookingClient).sendPaymentResult(eq("b1"), eq(r.id()), eq("SUCCESS"), any());
    }

    @Test
    void failedPaymentIsReportedAsFailed() {
        provider.configure(SimulatedPaymentProvider.Mode.FAILURE, 0);
        booking("b2", "u2", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
        PaymentResponse r = service.initiate("b2", "u2");
        assertEquals("FAILED", r.status());
        verify(bookingClient).sendPaymentResult(eq("b2"), eq(r.id()), eq("FAILED"), any());
    }

    @Test
    void bookingServiceDownDuringSyncDoesNotFailTheCustomerRequest() {
        booking("b3", "u3", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
        doThrow(new RuntimeException("booking down")).when(bookingClient).sendPaymentResult(any(), any(), any(), any());
        PaymentResponse r = service.initiate("b3", "u3");
        assertEquals("SUCCESS", r.status());
        assertEquals("FAILED_TO_SYNC", r.bookingSync());
    }

    @Test
    void repeatedInitiationReturnsSamePayment() {
        booking("b4", "u4", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
        PaymentResponse a = service.initiate("b4", "u4");
        PaymentResponse b = service.initiate("b4", "u4");
        assertEquals(a.id(), b.id());
        verify(bookingClient, times(1)).sendPaymentResult(any(), any(), any(), any());
    }

    @Test
    void cannotPayAnotherCustomersOrExpiredBooking() {
        booking("b5", "owner", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
        assertEquals("BOOKING_NOT_FOUND", assertThrows(BusinessException.class, () -> service.initiate("b5", "intruder")).getCode());
        booking("b6", "u6", "PENDING_PAYMENT", Instant.now().minus(1, ChronoUnit.MINUTES));
        assertEquals("BOOKING_NOT_PAYABLE", assertThrows(BusinessException.class, () -> service.initiate("b6", "u6")).getCode());
    }

    @Test
    void pendingPaymentCompletedByDuplicateSignedWebhooksIsAppliedOnce() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.PENDING, 0);
        booking("b7", "u7", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
        PaymentResponse pending = service.initiate("b7", "u7");
        assertEquals("PENDING", pending.status());

        String body = "{\"eventId\":\"evt-1\",\"providerReference\":\"" + pending.providerReference() + "\",\"status\":\"SUCCESS\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.add(SimulatedPaymentProvider.SIGNATURE_HEADER, sign("test-secret", body));
        service.handleWebhook(body, headers);
        service.handleWebhook(body, headers); // duplicate
        assertEquals("SUCCESS", service.get(pending.id(), "u7").status());
        verify(bookingClient, times(1)).sendPaymentResult(eq("b7"), eq(pending.id()), eq("SUCCESS"), any());
    }

    @Test
    void webhookWithBadSignatureIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SimulatedPaymentProvider.SIGNATURE_HEADER, "deadbeef");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleWebhook("{\"eventId\":\"x\",\"providerReference\":\"y\",\"status\":\"SUCCESS\"}", headers));
        assertEquals("INVALID_SIGNATURE", ex.getCode());
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
