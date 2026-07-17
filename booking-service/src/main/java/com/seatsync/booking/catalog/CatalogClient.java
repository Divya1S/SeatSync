package com.seatsync.booking.catalog;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "catalog", url = "${catalog.base-url}")
public interface CatalogClient {

    @GetMapping("/api/catalog/events/{id}")
    EventDto getEvent(@PathVariable("id") UUID id);

    @GetMapping("/api/catalog/events/{id}/seatmap")
    SeatMapDto getSeatMap(@PathVariable("id") UUID id);
}
