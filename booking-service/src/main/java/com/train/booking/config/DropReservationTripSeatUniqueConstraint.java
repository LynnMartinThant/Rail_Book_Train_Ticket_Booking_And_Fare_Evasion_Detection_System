package com.train.booking.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drops the unique constraint on reservations(trip_seat_id) if it exists.
 * Allows multiple reservation rows per seat (e.g. one EXPIRED, one RESERVED) so
 * users can book again after 1 min expiry without deleting the database.
 */
@Component
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
@Slf4j
public class DropReservationTripSeatUniqueConstraint implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    private static final String LEGACY_INDEX_NAME = "UK_3Q44SFBSAJOR2QVWVN4Y26MMH_INDEX_4";

    @Override
    public void run(ApplicationArguments args) {
        dropUniqueIndexOnReservationsTripSeatId();
    }

    private void dropUniqueIndexOnReservationsTripSeatId() {
        try {
            List<IndexInfo> indexes = jdbcTemplate.query(
                """
                    SELECT i.INDEX_SCHEMA, i.INDEX_NAME
                    FROM INFORMATION_SCHEMA.INDEXES i
                    INNER JOIN INFORMATION_SCHEMA.INDEX_COLUMNS ic
                      ON i.INDEX_SCHEMA = ic.INDEX_SCHEMA AND i.INDEX_NAME = ic.INDEX_NAME
                      AND i.TABLE_NAME = ic.TABLE_NAME
                    WHERE i.TABLE_NAME = 'RESERVATIONS' AND ic.COLUMN_NAME = 'TRIP_SEAT_ID'
                      AND i.INDEX_TYPE_NAME = 'UNIQUE INDEX'
                    """,
                (rs, rowNum) -> new IndexInfo(rs.getString("INDEX_SCHEMA"), rs.getString("INDEX_NAME"))
            );

            for (IndexInfo idx : indexes) {
                String schema = idx.schema != null ? idx.schema : "PUBLIC";
                String qualified = schema + "." + idx.name;
                try {
                    jdbcTemplate.execute("DROP INDEX IF EXISTS " + qualified);
                    log.info("Dropped unique index on reservations(trip_seat_id): {}", qualified);
                } catch (Exception ex) {
                    String quoted = quoteIdentifier(schema) + "." + quoteIdentifier(idx.name);
                    jdbcTemplate.execute("DROP INDEX IF EXISTS " + quoted);
                    log.info("Dropped unique index on reservations(trip_seat_id): {}", quoted);
                }
            }

            if (indexes.isEmpty()) {
                tryDropLegacyByName();
            }
        } catch (Exception e) {
            log.trace("Query for unique index failed, trying legacy name: {}", e.getMessage());
            tryDropLegacyByName();
        }
    }

    private void tryDropLegacyByName() {
        String[] toTry = {
            LEGACY_INDEX_NAME,
            "PUBLIC." + LEGACY_INDEX_NAME,
            quoteIdentifier("PUBLIC") + "." + quoteIdentifier(LEGACY_INDEX_NAME)
        };
        for (String name : toTry) {
            try {
                jdbcTemplate.execute("DROP INDEX IF EXISTS " + name);
                log.info("Dropped legacy unique index on reservations(trip_seat_id): {}", name);
                return;
            } catch (Exception e) {
                log.trace("DROP INDEX {} failed: {}", name, e.getMessage());
            }
        }
        try {
            jdbcTemplate.execute("ALTER TABLE reservations DROP CONSTRAINT IF EXISTS " + LEGACY_INDEX_NAME);
            log.info("Dropped legacy unique constraint on reservations(trip_seat_id)");
        } catch (Exception e) {
            log.trace("DROP CONSTRAINT failed: {}", e.getMessage());
        }
    }

    private static String quoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    private record IndexInfo(String schema, String name) {}
}
