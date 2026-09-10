package com.recoverysystem.domain.entity;

import com.recoverysystem.domain.enums.TimeOfDayPreference;
import com.recoverysystem.domain.enums.WaitlistEntryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "waitlist_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "current_appointment_id", nullable = false)
    private Long currentAppointmentId;

    @Column(name = "appointment_type_id", nullable = false)
    private Long appointmentTypeId;

    @Column(name = "preferred_provider_id")
    private Long preferredProviderId;

    @Column(name = "earliest_appointment_date", nullable = false)
    private LocalDate earliestAppointmentDate;

    @Column(name = "latest_appointment_date", nullable = false)
    private LocalDate latestAppointmentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_time_of_day", nullable = false, length = 10)
    private TimeOfDayPreference preferredTimeOfDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WaitlistEntryStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
