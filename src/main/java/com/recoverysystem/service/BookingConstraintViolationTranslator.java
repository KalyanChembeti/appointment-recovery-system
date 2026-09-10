package com.recoverysystem.service;

import com.recoverysystem.exception.PatientDoubleBookedException;
import com.recoverysystem.exception.ProviderDoubleBookedException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

final class BookingConstraintViolationTranslator {

    private static final String PROVIDER_OVERLAP_CONSTRAINT = "no_provider_overlap";
    private static final String PATIENT_OVERLAP_CONSTRAINT = "no_patient_overlap";

    private BookingConstraintViolationTranslator() {
    }

    static RuntimeException translate(DataIntegrityViolationException exception) {
        String constraintName = findConstraintName(exception);
        if (PROVIDER_OVERLAP_CONSTRAINT.equals(constraintName)) {
            return new ProviderDoubleBookedException(exception);
        }
        if (PATIENT_OVERLAP_CONSTRAINT.equals(constraintName)) {
            return new PatientDoubleBookedException(exception);
        }
        return exception;
    }

    private static String findConstraintName(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null) {
                return constraintViolation.getConstraintName();
            }

            String message = current.getMessage();
            if (message != null) {
                if (message.contains(PROVIDER_OVERLAP_CONSTRAINT)) {
                    return PROVIDER_OVERLAP_CONSTRAINT;
                }
                if (message.contains(PATIENT_OVERLAP_CONSTRAINT)) {
                    return PATIENT_OVERLAP_CONSTRAINT;
                }
            }

            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
