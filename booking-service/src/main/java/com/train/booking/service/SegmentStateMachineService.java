package com.train.booking.service;

import com.train.booking.domain.SegmentState;
import com.train.booking.domain.SegmentTransition;
import com.train.booking.domain.TripSegment;
import com.train.booking.repository.SegmentTransitionRepository;
import com.train.booking.repository.TripSegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SegmentStateMachineService implements SegmentStateMachine {

    private final TripSegmentRepository tripSegmentRepository;
    private final SegmentTransitionRepository segmentTransitionRepository;

    @Override
    @Transactional
    public void transition(Long segmentId, SegmentState nextState, String trigger, String reason, String correlationId) {
        TripSegment segment = tripSegmentRepository.findById(segmentId)
            .orElseThrow(() -> new IllegalArgumentException("Segment not found: " + segmentId));
        SegmentState from = segment.getSegmentState();
        if (from == nextState) return;
        segment.setSegmentState(nextState);
        tripSegmentRepository.save(segment);
        segmentTransitionRepository.save(SegmentTransition.builder()
            .segmentId(segmentId)
            .fromState(from != null ? from.name() : null)
            .toState(nextState.name())
            .triggerEventType(trigger != null ? trigger : "manual")
            .reason(reason)
            .correlationId(correlationId)
            .build());
    }
}

