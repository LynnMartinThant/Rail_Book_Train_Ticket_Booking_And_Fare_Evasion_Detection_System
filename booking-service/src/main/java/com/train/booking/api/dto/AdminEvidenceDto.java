package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin evidence view: traceable timeline + narrative derived from structured movement events.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminEvidenceDto {
    private String correlationId;
    private String userId;
    /** Human-readable narrative lines (auditable evidence). */
    private List<String> narrative;
    /** Full timeline (ordered asc) for drill-down. */
    private List<MovementEventDto> timeline;
    /** Segment lifecycle transitions associated with this trace. */
    private List<SegmentTransitionDto> segmentTransitions;
}

