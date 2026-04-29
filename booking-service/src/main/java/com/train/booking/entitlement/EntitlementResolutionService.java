package com.train.booking.entitlement;

import com.train.booking.domain.Reservation;
import com.train.booking.domain.TripSegment;

import java.util.List;
import java.util.Map;

public interface EntitlementResolutionService {
    EntitlementResolution resolve(
        TripSegment segment,
        List<Reservation> internalTickets,
        Map<String, Object> explanationRoot
    );
}
