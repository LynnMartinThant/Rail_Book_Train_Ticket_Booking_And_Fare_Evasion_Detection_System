package com.train.booking.confidence;

public record AnomalyEvidence( // uncertainty metrics
    boolean outOfOrderEvents,
    boolean missingEntry,
    boolean missingExit,
    boolean sparseReporting, 
    boolean implausibleJump
) {}

