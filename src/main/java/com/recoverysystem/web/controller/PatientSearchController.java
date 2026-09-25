package com.recoverysystem.web.controller;

import com.recoverysystem.service.PatientSearchQueryService;
import com.recoverysystem.web.dto.PatientSearchResponse;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients")
public class PatientSearchController {

    private final PatientSearchQueryService patientSearchQueryService;

    public PatientSearchController(PatientSearchQueryService patientSearchQueryService) {
        this.patientSearchQueryService = patientSearchQueryService;
    }

    @GetMapping
    ResponseEntity<List<PatientSearchResponse>> searchPatients(
            @RequestParam @Size(min = 2) String query) {
        // This proposed staff-only endpoint is a non-catalog addition: the locked API catalog
        // contains no patient-search path, while receptionist booking requires a patient ID.
        return ResponseEntity.ok(patientSearchQueryService.searchPatients(query));
    }
}
