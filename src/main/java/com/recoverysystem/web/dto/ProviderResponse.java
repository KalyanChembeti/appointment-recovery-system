package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.Provider;

public record ProviderResponse(
        Long id,
        Long userId,
        Long specialtyId,
        String licenseNumber,
        String qualifications) {

    public static ProviderResponse from(Provider provider) {
        return new ProviderResponse(
                provider.getId(),
                provider.getUserId(),
                provider.getSpecialtyId(),
                provider.getLicenseNumber(),
                provider.getQualifications());
    }
}
