package com.recoverysystem.web.dto;

import com.recoverysystem.domain.enums.UserRole;

public record CurrentUserResponse(Long userId, UserRole role) {
}
