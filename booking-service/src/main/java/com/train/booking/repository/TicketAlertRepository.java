package com.train.booking.repository;

import com.train.booking.domain.TicketAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketAlertRepository extends JpaRepository<TicketAlert, Long> {

    List<TicketAlert> findByUserIdOrderByCreatedAtDesc(String userId);
}
