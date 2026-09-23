package com.recoverysystem.web.controller;

import com.recoverysystem.service.ReferenceDataQueryService;
import com.recoverysystem.web.dto.AppointmentTypeResponse;
import com.recoverysystem.web.dto.ProviderListResponse;
import com.recoverysystem.web.dto.SpecialtyResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReferenceDataController {

    private final ReferenceDataQueryService referenceDataQueryService;

    public ReferenceDataController(ReferenceDataQueryService referenceDataQueryService) {
        this.referenceDataQueryService = referenceDataQueryService;
    }

    @GetMapping("/api/specialties")
    ResponseEntity<List<SpecialtyResponse>> listSpecialties() {
        return ResponseEntity.ok(referenceDataQueryService.findSpecialties());
    }

    @GetMapping("/api/appointment-types")
    ResponseEntity<List<AppointmentTypeResponse>> listAppointmentTypes(
            @RequestParam(required = false) Long specialtyId) {
        return ResponseEntity.ok(
                referenceDataQueryService.findAppointmentTypes(specialtyId));
    }

    @GetMapping("/api/providers")
    ResponseEntity<List<ProviderListResponse>> listProviders(
            @RequestParam(required = false) Long specialtyId) {
        return ResponseEntity.ok(referenceDataQueryService.findProviders(specialtyId));
    }
}
