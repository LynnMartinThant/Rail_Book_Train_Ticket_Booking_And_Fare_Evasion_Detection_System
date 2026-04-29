package com.train.booking.movement.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PassengerMovementSnapshotRepository extends JpaRepository<PassengerMovementSnapshot, String> {
}

