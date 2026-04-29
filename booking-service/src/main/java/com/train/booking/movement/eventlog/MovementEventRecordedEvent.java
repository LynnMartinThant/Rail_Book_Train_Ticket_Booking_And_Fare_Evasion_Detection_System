package com.train.booking.movement.eventlog;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class MovementEventRecordedEvent extends ApplicationEvent {
    private final MovementEventEnvelope envelope;

    public MovementEventRecordedEvent(Object source, MovementEventEnvelope envelope) {
        super(source);
        this.envelope = envelope;
    }
}

