package com.train.booking.repository;

import com.train.booking.domain.PaymentSequence;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentSequenceRepository extends JpaRepository<PaymentSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentSequence p where p.sequenceDate = :date")
    Optional<PaymentSequence> findBySequenceDateForUpdate(@Param("date") LocalDate date);

    Optional<PaymentSequence> findBySequenceDate(LocalDate date);
}
