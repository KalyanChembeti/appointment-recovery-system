package com.recoverysystem.web.dto;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.enums.SlotOfferStatus;
import java.time.Instant;

public record AcceptOfferResponse(
        Long slotOfferId,
        String status,
        Long appointmentId,
        Long patientId,
        Long providerId,
        Long appointmentTypeId,
        Instant startAt,
        Instant endAt) {

    public static AcceptOfferResponse from(Long slotOfferId, Appointment appointment) {
        return new AcceptOfferResponse(
                slotOfferId,
                SlotOfferStatus.ACCEPTED.name(),
                appointment.getId(),
                appointment.getPatientId(),
                appointment.getProviderId(),
                appointment.getAppointmentTypeId(),
                appointment.getStartAt(),
                appointment.getEndAt());
    }
}
