package com.recoverysystem.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final Pattern STRONG_PASSWORD = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,128}$",
            Pattern.DOTALL);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || STRONG_PASSWORD.matcher(value).matches();
    }
}
