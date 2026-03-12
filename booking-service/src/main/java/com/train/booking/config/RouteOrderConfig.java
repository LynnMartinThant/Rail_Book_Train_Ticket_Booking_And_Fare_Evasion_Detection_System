package com.train.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Ordered list of stations on the demo route for ticket coverage validation.
 * Segment is covered if ticket origin <= segment origin and ticket destination >= segment destination (by index).
 */
@Configuration
@ConfigurationProperties(prefix = "booking.route")
public class RouteOrderConfig {

    /** Station names in journey order, e.g. Sheffield, Rotherham, Doncaster */
    private List<String> stationOrder = List.of("Sheffield", "Rotherham", "Doncaster");

    public List<String> getStationOrder() {
        return stationOrder;
    }

    public void setStationOrder(List<String> stationOrder) {
        this.stationOrder = stationOrder;
    }

    public int indexOf(String station) {
        if (station == null) return -1;
        for (int i = 0; i < stationOrder.size(); i++) {
            if (station.equalsIgnoreCase(stationOrder.get(i))) return i;
        }
        return -1;
    }

    /** True if ticket (fromStation → toStation) covers segment (segmentOrigin → segmentDest) in route order. */
    public boolean ticketCoversSegment(String ticketOrigin, String ticketDest, String segmentOrigin, String segmentDest) {
        int to = indexOf(ticketOrigin);
        int td = indexOf(ticketDest);
        int so = indexOf(segmentOrigin);
        int sd = indexOf(segmentDest);
        if (to < 0 || td < 0 || so < 0 || sd < 0) return false;
        return to <= so && td >= sd;
    }

    /** True if ticket is "short" for the segment: covers part but not full (ticket dest before segment dest). */
    public boolean ticketIsShortForSegment(String ticketOrigin, String ticketDest, String segmentOrigin, String segmentDest) {
        int to = indexOf(ticketOrigin);
        int td = indexOf(ticketDest);
        int so = indexOf(segmentOrigin);
        int sd = indexOf(segmentDest);
        if (to < 0 || td < 0 || so < 0 || sd < 0) return true; // unknown station → treat as short
        if (to <= so && td >= sd) return false; // full coverage
        if (to <= so && td > so) return true;   // ticket covers start but not end
        return true; // no coverage or partial
    }
}
