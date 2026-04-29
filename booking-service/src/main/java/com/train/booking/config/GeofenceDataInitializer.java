package com.train.booking.config;

import com.train.booking.domain.Geofence;
import com.train.booking.repository.GeofenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds geofences for all stations on all lines (Hallam, Calder Valley, Hope Valley, Huddersfield, Wakefield).
 * One geofence per unique station name so admin can see/enforce all stations.
 */
@Component
@Profile("!test")
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class GeofenceDataInitializer implements CommandLineRunner {

    private final GeofenceRepository geofenceRepository;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Ensuring geofences for all stations (all lines)");
        // stationName -> (displayName, platform, lat, lon) – first occurrence wins for duplicates
        Map<String, Object[]> all = new LinkedHashMap<>();
        // Hallam Line
        put(all, "Leeds", "Leeds railway station", "1A", 53.7940, -1.5470);
        put(all, "Woodlesford", "Woodlesford", "2A", 53.7570, -1.4435);
        put(all, "Castleford", "Castleford", "2B", 53.7250, -1.3560);
        put(all, "Normanton", "Normanton", "3A", 53.7010, -1.4230);
        put(all, "Wakefield Kirkgate", "Wakefield Kirkgate railway station", "3B", 53.6790, -1.4880);
        put(all, "Darton", "Darton", "1B", 53.5880, -1.5310);
        put(all, "Barnsley", "Barnsley railway station", "2A", 53.5540, -1.4790);
        put(all, "Wombwell", "Wombwell", "2B", 53.5210, -1.4040);
        put(all, "Elsecar", "Elsecar", "1A", 53.4989, -1.4271);
        put(all, "Chapeltown", "Chapeltown", "1B", 53.4650, -1.4720);
        put(all, "Meadowhall Interchange", "Meadowhall Interchange", null, 53.4167, -1.4141);
        put(all, "Sheffield", "Sheffield railway station", null, 53.37823, -1.46210);
        // Calder Valley
        put(all, "Bramley", "Bramley", "1A", 53.7610, -1.6370);
        put(all, "New Pudsey", "New Pudsey", "2A", 53.7928, -1.6804);
        put(all, "Bradford Interchange", "Bradford Interchange", "2B", 53.7910, -1.7500);
        put(all, "Low Moor", "Low Moor", "1B", 53.7490, -1.7520);
        put(all, "Halifax", "Halifax railway station", "2A", 53.7207, -1.8538);
        put(all, "Sowerby Bridge", "Sowerby Bridge", "2B", 53.7080, -1.9070);
        put(all, "Mytholmroyd", "Mytholmroyd", "1A", 53.7300, -1.9820);
        put(all, "Hebden Bridge", "Hebden Bridge", "1B", 53.7400, -2.0140);
        put(all, "Todmorden", "Todmorden", "2A", 53.7130, -2.0970);
        put(all, "Walsden", "Walsden", "1A", 53.6950, -2.1490);
        put(all, "Littleborough", "Littleborough", "2B", 53.6430, -2.0950);
        put(all, "Smithy Bridge", "Smithy Bridge", "1B", 53.6330, -2.1130);
        put(all, "Rochdale", "Rochdale railway station", "2A", 53.6104, -2.1554);
        put(all, "Manchester Victoria", "Manchester Victoria station", "3B", 53.4872, -2.2424);
        // Hope Valley
        put(all, "Dore & Totley", "Dore & Totley", "1A", 53.3270, -1.5150);
        put(all, "Grindleford", "Grindleford", "2A", 53.3050, -1.6260);
        put(all, "Hathersage", "Hathersage", "1B", 53.3300, -1.6530);
        put(all, "Bamford", "Bamford", "2B", 53.3390, -1.6890);
        put(all, "Hope", "Hope", "1A", 53.3480, -1.7440);
        put(all, "Edale", "Edale", "2A", 53.3640, -1.8160);
        put(all, "Chinley", "Chinley railway station", "1B", 53.3400, -1.9440);
        put(all, "New Mills Central", "New Mills Central", "2A", 53.3640, -2.0060);
        put(all, "Marple", "Marple", "1A", 53.3960, -2.0620);
        put(all, "Manchester Piccadilly", "Manchester Piccadilly station", "2B", 53.4770, -2.2305);
        // Leeds → Manchester via Huddersfield
        put(all, "Cross Gates", "Cross Gates", "1A", 53.8060, -1.4520);
        put(all, "Garforth", "Garforth", "2A", 53.7960, -1.3820);
        put(all, "East Garforth", "East Garforth", "1B", 53.7920, -1.3600);
        put(all, "Micklefield", "Micklefield", "2B", 53.7910, -1.3260);
        put(all, "Church Fenton", "Church Fenton", "1A", 53.8260, -1.2280);
        put(all, "Huddersfield", "Huddersfield railway station", "2A", 53.6484, -1.7844);
        put(all, "Slaithwaite", "Slaithwaite", "1B", 53.6240, -1.8820);
        put(all, "Marsden", "Marsden", "2B", 53.6020, -1.9300);
        put(all, "Greenfield", "Greenfield", "1A", 53.5380, -2.0120);
        put(all, "Mossley", "Mossley", "2A", 53.5150, -2.0410);
        put(all, "Stalybridge", "Stalybridge", "1B", 53.4840, -2.0630);
        // Sheffield → Leeds via Wakefield
        put(all, "Rotherham Central", "Rotherham Central", "2A", 53.4322, -1.3634);
        put(all, "Swinton", "Swinton", "1B", 53.4880, -1.3120);
        put(all, "Moorthorpe", "Moorthorpe", "2B", 53.5950, -1.3040);

        int added = 0;
        for (Map.Entry<String, Object[]> e : all.entrySet()) {
            String stationName = e.getKey();
            if (!geofenceRepository.findAllByStationName(stationName).isEmpty()) continue;
            Object[] v = e.getValue();
            String displayName = (String) v[0];
            String platform = (String) v[1];
            double lat = (Double) v[2];
            double lon = (Double) v[3];
            Geofence g = Geofence.builder()
                .name(displayName)
                .stationName(stationName)
                .platform(platform)
                .latitude(lat)
                .longitude(lon)
                .radiusMeters(150)
                .build();
            geofenceRepository.save(g);
            added++;

            if ("Sheffield".equals(stationName)) {
                Object[][] platforms = {
                    { "1", 53.37833, -1.46190 },
                    { "2", 53.37825, -1.46195 },
                    { "3", 53.37815, -1.46205 },
                    { "4", 53.37810, -1.46210 },
                    { "5", 53.37805, -1.46218 },
                    { "6", 53.37800, -1.46225 },
                    { "7", 53.37795, -1.46232 },
                    { "8", 53.37790, -1.46240 },
                };
                for (Object[] p : platforms) {
                    Geofence pf = Geofence.builder()
                        .name("Sheffield Platform " + p[0])
                        .stationName("Sheffield")
                        .platform((String) p[0])
                        .latitude((Double) p[1])
                        .longitude((Double) p[2])
                        .radiusMeters(20)
                        .build();
                    geofenceRepository.save(pf);
                    added++;
                }
            } else if ("Meadowhall Interchange".equals(stationName)) {
                Object[][] platforms = {
                    { "1", 53.41685, -1.41430 },
                    { "2", 53.41680, -1.41420 },
                    { "3", 53.41672, -1.41410 },
                    { "4", 53.41665, -1.41400 },
                };
                for (Object[] p : platforms) {
                    Geofence pf = Geofence.builder()
                        .name("Meadowhall Platform " + p[0])
                        .stationName("Meadowhall Interchange")
                        .platform((String) p[0])
                        .latitude((Double) p[1])
                        .longitude((Double) p[2])
                        .radiusMeters(25)
                        .build();
                    geofenceRepository.save(pf);
                    added++;
                }
            }
        }
        log.info("Geofence init done: {} added (total {} geofences)", added, geofenceRepository.count());
    }

    private static void put(Map<String, Object[]> map, String stationName, String displayName, String platform, double lat, double lon) {
        map.putIfAbsent(stationName, new Object[]{ displayName, platform, lat, lon });
    }
}
