package com.tickethub.event;

import com.tickethub.common.error.BusinessException;
import com.tickethub.event.client.BookingClient;
import com.tickethub.event.domain.Event;
import com.tickethub.event.domain.Show;
import com.tickethub.event.domain.Venue;
import com.tickethub.event.repo.EventRepository;
import com.tickethub.event.repo.ShowRepository;
import com.tickethub.event.repo.VenueRepository;
import com.tickethub.event.repo.VenueSeatRepository;
import com.tickethub.event.service.ShowService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShowServiceTest {

    private final ShowRepository shows = mock(ShowRepository.class);
    private final ShowService service = new ShowService(shows, mock(EventRepository.class),
            mock(VenueRepository.class), mock(VenueSeatRepository.class), mock(BookingClient.class));

    private Show show(Event.Status eventStatus, Show.Status showStatus) {
        Event e = new Event();
        e.setId(1L);
        e.setTitle("T");
        e.setStatus(eventStatus);
        Venue v = new Venue();
        v.setId(2L);
        v.setName("V");
        Show s = new Show();
        s.setId(3L);
        s.setEvent(e);
        s.setVenue(v);
        s.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
        s.setEndTime(Instant.now().plus(2, ChronoUnit.DAYS));
        s.setPrice(new java.math.BigDecimal("100"));
        s.setCurrency("INR");
        s.setStatus(showStatus);
        when(shows.findDetailedById(3L)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void showOpensOnlyForPublishedEvent() {
        show(Event.Status.DRAFT, Show.Status.SCHEDULED);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.open(3L));
        assertEquals("EVENT_NOT_PUBLISHED", ex.getCode());
    }

    @Test
    void scheduledShowOpensWhenEventPublished() {
        show(Event.Status.PUBLISHED, Show.Status.SCHEDULED);
        assertEquals("OPEN", service.open(3L).status());
    }

    @Test
    void openShowCannotBeOpenedAgain() {
        show(Event.Status.PUBLISHED, Show.Status.OPEN);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.open(3L));
        assertEquals("INVALID_SHOW_STATE", ex.getCode());
    }
}
