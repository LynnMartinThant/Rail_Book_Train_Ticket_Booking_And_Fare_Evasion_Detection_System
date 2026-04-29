package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity // geofence event
@Table(name = "geofence_events", indexes = {
    @Index(name = "idx_geofence_event_user_created", columnList = "user_id, created_at"),
    @Index(name = "idx_geofence_event_geofence", columnList = "geofence_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeofenceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "geofence_id", nullable = false)
    private Geofence geofence;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    
    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    public enum EventType { ENTERED, EXITED }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
