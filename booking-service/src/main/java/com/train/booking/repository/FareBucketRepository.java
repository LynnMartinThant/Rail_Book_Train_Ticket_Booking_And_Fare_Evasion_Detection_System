package com.train.booking.repository;

import com.train.booking.domain.FareBucket; //allow save(), findById(), findAll(), and delete() for fare buckets

import java.util.List;

public interface FareBucketRepository extends org.springframework.data.jpa.repository.JpaRepository<FareBucket, Long> {

    List<FareBucket> findByTripIdOrderByDisplayOrderAsc(Long tripId);
}
