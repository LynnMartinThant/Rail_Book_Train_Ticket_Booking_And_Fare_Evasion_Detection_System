package com.train.booking.service;

import com.train.booking.domain.SegmentState;

public interface SegmentStateMachine {
    void transition(Long segmentId, SegmentState nextState, String trigger, String reason, String correlationId);
}

