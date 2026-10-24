package com.tickethub.event.config;

import com.tickethub.event.domain.Event;
import com.tickethub.event.domain.Show;
import com.tickethub.event.domain.Venue;
import com.tickethub.event.domain.VenueSeat;
import com.tickethub.event.repo.EventRepository;
import com.tickethub.event.repo.ShowRepository;
import com.tickethub.event.repo.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Demo data so the end-to-end flow can be shown immediately. Disable with SEED_ENABLED=false. */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final boolean enabled;
    private final VenueRepository venues;
    private final EventRepository events;
    private final ShowRepository shows;

    public DataSeeder(@Value("${tickethub.seed.enabled:true}") boolean enabled,
                      VenueRepository venues, EventRepository events, ShowRepository shows) {
        this.enabled = enabled;
        this.venues = venues;
        this.events = events;
        this.shows = shows;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || events.count() > 0 || venues.count() > 0) {
            return;
        }
        Venue venue = new Venue();
        venue.setName("Grand Hall");
        venue.setCity("Bengaluru");
        venue.setAddress("MG Road");
        for (String row : new String[]{"A", "B", "C"}) {
            for (int n = 1; n <= 6; n++) {
                VenueSeat seat = new VenueSeat();
                seat.setRowLabel(row);
                seat.setSeatNumber(n);
                seat.setSeatType("A".equals(row) ? VenueSeat.SeatType.PREMIUM : VenueSeat.SeatType.STANDARD);
                venue.addSeat(seat);
            }
        }
        venue = venues.save(venue);

        Event event = new Event();
        event.setTitle("Sample Live Concert");
        event.setDescription("Demo event created by the seeder");
        event.setCategory("CONCERT");
        event.setStatus(Event.Status.PUBLISHED);
        event = events.save(event);

        Show show = new Show();
        show.setEvent(event);
        show.setVenue(venue);
        show.setStartTime(Instant.now().plus(7, ChronoUnit.DAYS));
        show.setEndTime(show.getStartTime().plus(Duration.ofHours(3)));
        show.setPrice(new BigDecimal("500.00"));
        show.setCurrency("INR");
        show.setStatus(Show.Status.OPEN);
        shows.save(show);
        log.info("Seeded demo venue, event and show");
    }
}
