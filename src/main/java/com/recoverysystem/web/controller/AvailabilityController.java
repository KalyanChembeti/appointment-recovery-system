package com.recoverysystem.web.controller;

import com.recoverysystem.service.AvailabilityQueryService;
import com.recoverysystem.service.TimeSlot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityQueryService availabilityQueryService;

    public AvailabilityController(AvailabilityQueryService availabilityQueryService) {
        this.availabilityQueryService = availabilityQueryService;
    }

    // Intentional extension beyond the locked API catalog: this narrowly scoped query
    // exposes one provider's open slots for one appointment type on one date.
    @GetMapping
    ResponseEntity<List<TimeSlot>> findAvailableSlots(
            @RequestParam Long providerId,
            @RequestParam Long appointmentTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(availabilityQueryService.findAvailableSlots(
                providerId, appointmentTypeId, date));
    }
}
