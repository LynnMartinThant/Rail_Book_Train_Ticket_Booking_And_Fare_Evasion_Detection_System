package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeofenceDto {
    private Long id;
    private String name;
    private String stationName;
    private String platform;
    private Double latitude;
    private Double longitude;
    private Integer radiusMeters;
}
