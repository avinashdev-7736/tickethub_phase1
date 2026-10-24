package com.tickethub.event.repo;

import com.tickethub.event.domain.VenueSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueSeatRepository extends JpaRepository<VenueSeat, Long> {
    List<VenueSeat> findByVenueIdOrderByRowLabelAscSeatNumberAsc(Long venueId);
}
