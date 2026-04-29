package com.train.booking.confidence;

public record MovementEvidence( // geofence evidence
    boolean entryPresent,
    boolean exitPresent,
    int sampleCount,
    Double gpsAccuracyMeters
) {}

