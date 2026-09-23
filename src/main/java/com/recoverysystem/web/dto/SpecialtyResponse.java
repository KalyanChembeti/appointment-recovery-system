package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.Specialty;

public record SpecialtyResponse(Long id, String name, String description) {

    public static SpecialtyResponse from(Specialty specialty) {
        return new SpecialtyResponse(
                specialty.getId(), specialty.getName(), specialty.getDescription());
    }
}
