package com.recoverysystem.web.dto;

import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path) {
}
