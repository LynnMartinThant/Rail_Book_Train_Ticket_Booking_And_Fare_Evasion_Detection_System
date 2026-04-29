package com.train.booking.confidence;

public record RouteEvidence(
    boolean routeAligned,
    boolean stationOrderValid,
    boolean hasUnexplainedJump
) {}

