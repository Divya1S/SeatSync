package com.seatsync.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVenueRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String city,
        @NotBlank @Size(max = 500) String address) {
}
