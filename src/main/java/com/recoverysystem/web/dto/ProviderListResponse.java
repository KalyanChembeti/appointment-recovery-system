package com.recoverysystem.web.dto;

public record ProviderListResponse(
        Long id, Long userId, Long specialtyId, String displayName) {
}
