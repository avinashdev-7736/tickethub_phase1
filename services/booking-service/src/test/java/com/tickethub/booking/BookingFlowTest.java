package com.tickethub.booking;

import com.tickethub.booking.client.EventClient;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.dto.Dtos.*;
import com.tickethub.booking.repo.BookingRepository;
import com.tickethub.booking.repo.SeatReservationRepository;
import com.tickethub.booking.service.BookingEventPublisher;
import com.tickethub.booking.service.BookingService;
import com.tickethub.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bookingtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "eureka.client.enabled=false",
        "tickethub.kafka.topic.auto-create=false",
        "booking.scan-interval-ms=3600000"
})
class BookingFlowTest {

    @Autowired BookingService service;
    @Autowired BookingRepository bookings;
    @Autowired SeatReservationRepository reservations;
    @MockBean EventClient eventClient;
    @MockBean BookingEventPublisher publisher;

    @BeforeEach
    void stubShow() {
        when(eventClient.getShow(anyLong())).thenAnswer(inv -> new ShowInfo(inv.getArgument(0), "OPEN", "Test Show",
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("100.00"), "INR",
                List.of(new ShowSeat(1L, "A1"), new ShowSeat(2L, "A2"), new ShowSeat(3L, "A3"))));
    }

    private CreateBookingRequest req(long showId, Long... seats) {
        return new CreateBookingRequest(showId, List.of(seats));
    }

    @Test
    void exactlyOneOfTwoConcurrentRequestsWinsTheSeat() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> a = () -> attempt(start, "cust-a", 101L);
        Callable<String> b = () -> attempt(start, "cust-b", 101L);
        Future<String> f1 = pool.submit(a);
        Future<String> f2 = pool.submit(b);
        start.countDown();
        List<String> results = List.of(f1.get(), f2.get());
        pool.shutdown();
        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(1, results.stream().filter("SEAT_UNAVAILABLE"::equals).count(), results.toString());
    }

    private String attempt(CountDownLatch start, String customer, long showId) throws InterruptedException {
        start.await();
        try {
            service.create(req(showId, 1L), customer, customer + "@example.com", null);
            return "OK";
        } catch (BusinessException e) {
            return e.getCode();
        }
    }

    @Test
    void amountIsComputedByServerFromShowPrice() {
        BookingResponse r = service.create(req(102L, 1L, 2L), "cust-c", "c@example.com", null).booking();
        assertEquals(0, new BigDecimal("200.00").compareTo(r.amount()));
        assertEquals("PENDING_PAYMENT", r.status());
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalBooking() {
        CreateResult first = service.create(req(103L, 1L), "cust-d", "d@example.com", "key-1");
        CreateResult second = service.create(req(103L, 1L), "cust-d", "d@example.com", "key-1");
        assertFalse(first.replay());
        assertTrue(second.replay());
        assertEquals(first.booking().id(), second.booking().id());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req(103L, 2L), "cust-d", "d@example.com", "key-1"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", ex.getCode());
    }

    @Test
    void failedPaymentReleasesSeatsSoTheyCanBeBookedAgain() {
        BookingResponse r = service.create(req(104L, 3L), "cust-e", "e@example.com", null).booking();
        service.applyPaymentResult(r.id(), new PaymentResultRequest("pay-1", PaymentOutcome.FAILED, "declined"));
        assertEquals(Booking.Status.PAYMENT_FAILED, bookings.findById(r.id()).orElseThrow().getStatus());
        assertDoesNotThrow(() -> service.create(req(104L, 3L), "cust-f", "f@example.com", null));
    }

    @Test
    void successfulPaymentConfirmsBookingAndPublishesOnce() {
        BookingResponse r = service.create(req(105L, 1L), "cust-g", "g@example.com", null).booking();
        PaymentResultRequest ok = new PaymentResultRequest("pay-2", PaymentOutcome.SUCCESS, null);
        service.applyPaymentResult(r.id(), ok);
        service.applyPaymentResult(r.id(), ok); // duplicate callback
        assertEquals(Booking.Status.CONFIRMED, bookings.findById(r.id()).orElseThrow().getStatus());
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void otherCustomerCannotSeeBooking() {
        BookingResponse r = service.create(req(106L, 1L), "cust-h", "h@example.com", null).booking();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.get(r.id(), "someone-else"));
        assertEquals("BOOKING_NOT_FOUND", ex.getCode());
    }

    @Test
    void cancelReleasesSeats() {
        BookingResponse r = service.create(req(107L, 1L), "cust-i", "i@example.com", null).booking();
        service.cancel(r.id(), "cust-i");
        assertTrue(reservations.findByShowId(107L).isEmpty());
    }
}
