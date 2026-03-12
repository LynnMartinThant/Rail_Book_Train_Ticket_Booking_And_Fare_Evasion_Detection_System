package com.train.booking.config;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Defines all Northern lines with station order. Used to find trips that serve a segment (fromStation → toStation).
 */
@Component
@Getter
public class RouteConfig {

    /** A route: ordered list of stations from first to last. */
    public static class Route {
        private final String from;  // first station
        private final String to;    // last station
        private final List<String> stations;

        public Route(String from, String to, List<String> stations) {
            this.from = from;
            this.to = to;
            this.stations = new ArrayList<>(stations);
        }

        /** True if this route serves segment origin → destination (origin before destination). */
        public boolean servesSegment(String origin, String destination) {
            int i = indexOf(origin);
            int j = indexOf(destination);
            return i >= 0 && j >= 0 && i < j;
        }

        private int indexOf(String station) {
            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).equalsIgnoreCase(station.trim())) return i;
            }
            return -1;
        }

        public String getFrom() { return from; }
        public String getTo() { return to; }
    }

    private final List<Route> routes = new ArrayList<>();

    public RouteConfig() {
        // 1. Hallam Line: Leeds → Sheffield
        routes.add(new Route("Leeds", "Sheffield", List.of(
            "Leeds", "Woodlesford", "Castleford", "Normanton", "Wakefield Kirkgate", "Darton",
            "Barnsley", "Wombwell", "Elsecar", "Chapeltown", "Meadowhall Interchange", "Sheffield"
        )));
        // 2. Calder Valley: Leeds → Manchester Victoria
        routes.add(new Route("Leeds", "Manchester Victoria", List.of(
            "Leeds", "Bramley", "New Pudsey", "Bradford Interchange", "Low Moor", "Halifax",
            "Sowerby Bridge", "Mytholmroyd", "Hebden Bridge", "Todmorden", "Walsden", "Littleborough",
            "Smithy Bridge", "Rochdale", "Manchester Victoria"
        )));
        // 3. Hope Valley: Sheffield → Manchester Piccadilly
        routes.add(new Route("Sheffield", "Manchester Piccadilly", List.of(
            "Sheffield", "Dore & Totley", "Grindleford", "Hathersage", "Bamford", "Hope",
            "Edale", "Chinley", "New Mills Central", "Marple", "Manchester Piccadilly"
        )));
        // 4. Leeds → Manchester Victoria via Huddersfield
        routes.add(new Route("Leeds", "Manchester Victoria", List.of(
            "Leeds", "Cross Gates", "Garforth", "East Garforth", "Micklefield", "Church Fenton",
            "Huddersfield", "Slaithwaite", "Marsden", "Greenfield", "Mossley", "Stalybridge", "Manchester Victoria"
        )));
        // 5. Sheffield → Leeds via Wakefield (fast)
        routes.add(new Route("Sheffield", "Leeds", List.of(
            "Sheffield", "Meadowhall Interchange", "Rotherham Central", "Swinton", "Moorthorpe",
            "Wakefield Kirkgate", "Woodlesford", "Leeds"
        )));
    }

    /** Endpoint pairs (from, to) for routes that serve the segment origin → destination. */
    public List<EndpointPair> endpointPairsForSegment(String origin, String destination) {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return List.of();
        }
        String o = origin.trim();
        String d = destination.trim();
        if (o.equalsIgnoreCase(d)) return List.of();
        Set<EndpointPair> seen = new LinkedHashSet<>();
        for (Route r : routes) {
            if (r.servesSegment(o, d)) seen.add(new EndpointPair(r.getFrom(), r.getTo()));
        }
        return new ArrayList<>(seen);
    }

    public record EndpointPair(String from, String to) {}
}
