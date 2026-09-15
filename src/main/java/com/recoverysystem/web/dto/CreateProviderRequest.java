package com.recoverysystem.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProviderRequest(
        @NotNull Long userId,
        @NotNull Long specialtyId,
        @NotBlank String licenseNumber,
        String qualifications) {
}
