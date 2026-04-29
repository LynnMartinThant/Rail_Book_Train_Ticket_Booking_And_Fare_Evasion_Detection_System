package com.train.booking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Drops H2 check constraints on movement_event enums (event_type/source_layer).
 *
 * H2 + Hibernate often creates CHECK constraints for @Enumerated columns. When new enum values are added
 * (e.g. StationEntryValidated), H2 constraints are not automatically widened by ddl-auto=update,
 * causing SQL 23513 on insert. We drop CHECK constraints for this table to allow forward-compatible event evolution.
 */
@Component
@Profile("!test")
@Order(11)
@Slf4j
public class MovementEventConstraintMigration {

    private final JdbcTemplate jdbcTemplate;

    public MovementEventConstraintMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void dropMovementEventCheckConstraints() {
        try {
            List<String> names = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CONSTRAINTS " +
                    "WHERE TABLE_NAME = 'MOVEMENT_EVENT' AND CONSTRAINT_TYPE = 'CHECK'",
                String.class);
            for (String name : names) {
                tryDrop(name);
            }
            // Also attempt common generated names (best-effort).
            tryDrop("CONSTRAINT_5A");
        } catch (Exception e) {
            tryDrop("CONSTRAINT_5A");
        }
    }

    private void tryDrop(String constraintName) {
        try {
            jdbcTemplate.execute("ALTER TABLE movement_event DROP CONSTRAINT " + constraintName);
            log.info("Dropped check constraint {} on movement_event (allows new enum values)", constraintName);
        } catch (Exception e) {
            // ignore if constraint doesn't exist / already dropped / non-H2
        }
    }
}

