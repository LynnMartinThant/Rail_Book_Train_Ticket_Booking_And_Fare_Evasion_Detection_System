package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data  //admin view of notification from user
@Builder 
public class UserNotificationDto {
    private Long id;
    private String userId;
    private String message;
    private Instant createdAt;
    private Instant readAt;
}
