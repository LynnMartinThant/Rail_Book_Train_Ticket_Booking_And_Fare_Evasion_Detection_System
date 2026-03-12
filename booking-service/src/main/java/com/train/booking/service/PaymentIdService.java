package com.train.booking.service;

import com.train.booking.domain.PaymentSequence;
import com.train.booking.repository.PaymentSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Generates unique payment IDs in format dd/mm/yy-NNNN (e.g. 02/03/25-0015).
 */
@Service
@RequiredArgsConstructor
public class PaymentIdService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final PaymentSequenceRepository paymentSequenceRepository;

    @Transactional
    public String generateNextId() {
        LocalDate today = LocalDate.now();
        PaymentSequence seq = paymentSequenceRepository.findBySequenceDateForUpdate(today)
            .orElseGet(() -> paymentSequenceRepository.save(
                PaymentSequence.builder().sequenceDate(today).nextValue(1).build()
            ));
        int next = seq.getNextValue();
        seq.setNextValue(next + 1);
        paymentSequenceRepository.save(seq);
        String datePart = today.format(DATE_FORMAT);
        return datePart + "-" + String.format("%04d", next);
    }
}
