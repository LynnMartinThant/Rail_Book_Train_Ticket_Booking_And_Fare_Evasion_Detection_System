package com.train.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication //load controller, service, repo, etc..
@org.springframework.scheduling.annotation.EnableScheduling
public class BookingServiceApplication {

    public static void main(String[] args) {
        String activeProfiles = System.getProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE"));
        boolean core58 = activeProfiles != null && java.util.Arrays.stream(activeProfiles.split(","))
            .map(String::trim)
            .anyMatch("core58"::equalsIgnoreCase);
        SpringApplication.run(core58 ? Core58Application.class : BookingServiceApplication.class, args);
    }
}

