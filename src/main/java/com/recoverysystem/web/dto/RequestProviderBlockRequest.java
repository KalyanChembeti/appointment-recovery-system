package com.recoverysystem.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RequestProviderBlockRequest(
        Long providerId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        String reason) {
}
