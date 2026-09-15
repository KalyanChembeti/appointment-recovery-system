package com.recoverysystem.exception;

public class SpecialtyNotFoundException extends RuntimeException {

    public SpecialtyNotFoundException(Long specialtyId) {
        super("Specialty not found: " + specialtyId);
    }
}
