package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Created when user enters a station and the system (Drools rule) detects no ticket.
 * User must choose: BUY_TICKET, IGNORE, or SCAN_QR (bought elsewhere). If SCAN_QR, they scan ticket QR and we validate.
 */
@Entity
@Table(name = "station_entry_actions", indexes = {
    @Index(name = "idx_station_entry_user", columnList = "user_id"),
    @Index(name = "idx_station_entry_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationEntryAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "geofence_id", nullable = false)
    private Long geofenceId;

    @Column(name = "station_name", nullable = false)
    private String stationName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Set when user responds: IGNORE, BUY_TICKET, SCANNED_QR */
    @Enumerated(EnumType.STRING)
    @Column(name = "response_type")
    private ResponseType responseType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    /** When responseType=SCANNED_QR, the reservation ID that was validated from QR. Unique to prevent double-use of same ticket. */
    @Column(name = "qr_validated_reservation_id", unique = true)
    private Long qrValidatedReservationId;

    @Version
    @Column(name = "version")
    private Long version;

    public enum Status {
        PENDING_OPTION,  // waiting for user to choose Buy / Ignore / Scan QR
        IGNORED,
        BOUGHT,
        SCANNED_QR
    }

    public enum ResponseType {
        IGNORE,
        BUY_TICKET,
        SCAN_QR
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
