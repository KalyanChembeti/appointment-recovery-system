package com.recoverysystem.web.dto;

public record PatientSearchResponse(
        Long id,
        String email,
        String displayName) {
}
