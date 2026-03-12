package com.train.booking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Drops the H2 check constraint on trip_segments.fare_status so that the enum can accept
 * PENDING_RESOLUTION (and any future values). The constraint was created with only
 * PAID, UNDERPAID, UNPAID_TRAVEL and causes SQL 23513 on insert after adding PENDING_RESOLUTION.
 */
@Component
@Profile("!test")
@Order(10)
@Slf4j
public class FareStatusConstraintMigration {

    private final JdbcTemplate jdbcTemplate;

    public FareStatusConstraintMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void dropFareStatusCheckConstraint() {
        try {
            List<String> names = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'TRIP_SEGMENTS' AND CONSTRAINT_TYPE = 'CHECK'",
                String.class);
            for (String name : names) {
                tryDrop(name);
            }
            tryDrop("CONSTRAINT_F9");
        } catch (Exception e) {
            tryDrop("CONSTRAINT_F9");
        }
    }

    private void tryDrop(String constraintName) {
        try {
            jdbcTemplate.execute("ALTER TABLE trip_segments DROP CONSTRAINT " + constraintName);
            log.info("Dropped check constraint {} on trip_segments (allows PENDING_RESOLUTION)", constraintName);
        } catch (Exception e) {
            // ignore if constraint does not exist or already dropped
        }
    }
}
