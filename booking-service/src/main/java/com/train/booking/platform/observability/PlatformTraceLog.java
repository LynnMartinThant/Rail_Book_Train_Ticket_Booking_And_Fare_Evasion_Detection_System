package com.train.booking.platform.observability;

import org.slf4j.Logger;

/**
 * Structured trace fields for key pipeline actions (correlationId, eventId, userId, module).
 */
public final class PlatformTraceLog {

    private PlatformTraceLog() {
    }

    public static void info(Logger log, String module, String action, String outcome,
                            String correlationId, String eventId, String userId) {
        if (!log.isInfoEnabled()) return;
        log.info("platformTrace module={} action={} outcome={} correlationId={} eventId={} userId={}",
            module, action, outcome, nullToDash(correlationId), nullToDash(eventId), nullToDash(userId));
    }

    public static void warn(Logger log, String module, String action, String outcome,
                            String correlationId, String eventId, String userId, String detail) {
        log.warn("platformTrace module={} action={} outcome={} correlationId={} eventId={} userId={} detail={}",
            module, action, outcome, nullToDash(correlationId), nullToDash(eventId), nullToDash(userId), detail);
    }

    private static String nullToDash(String s) {
        return s != null ? s : "—";
    }
}
