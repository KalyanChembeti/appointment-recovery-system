package com.recoverysystem.web.dto;

import com.recoverysystem.domain.enums.UserRole;

public record RegisterResponse(Long userId, String email, UserRole role) {
}
