package com.seatsync.catalog.web.dto;

import com.seatsync.catalog.domain.PriceTier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SectionRequest(
        @NotBlank @Size(max = 64) String name,
        @Min(1) @Max(200) int rowCount,
        @Min(1) @Max(200) int seatsPerRow,
        @NotNull PriceTier priceTier,
        @NotNull @DecimalMin("0.00") BigDecimal price) {
}
