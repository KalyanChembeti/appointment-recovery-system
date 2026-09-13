package com.recoverysystem.web.dto;

import com.recoverysystem.web.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @StrongPassword String password) {

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=[PROTECTED]]";
    }
}
