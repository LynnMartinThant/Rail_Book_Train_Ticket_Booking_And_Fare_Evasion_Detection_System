package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "payment_sequence", uniqueConstraints = @UniqueConstraint(columnNames = "sequence_date"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @Column(name = "sequence_date", nullable = false, unique = true)
    private LocalDate sequenceDate;

    @Column(name = "next_value", nullable = false)
    private int nextValue;
}
