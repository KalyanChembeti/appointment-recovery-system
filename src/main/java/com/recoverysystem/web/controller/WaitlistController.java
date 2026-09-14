package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.exception.WaitlistEntryOwnershipException;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.WaitlistEntryCreationService;
import com.recoverysystem.service.WaitlistEntryModificationService;
import com.recoverysystem.service.WaitlistEntryRemovalService;
import com.recoverysystem.web.dto.JoinWaitlistRequest;
import com.recoverysystem.web.dto.ModifyWaitlistRequest;
import com.recoverysystem.web.dto.WaitlistEntryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    private final WaitlistEntryCreationService waitlistEntryCreationService;
    private final WaitlistEntryModificationService waitlistEntryModificationService;
    private final WaitlistEntryRemovalService waitlistEntryRemovalService;
    private final WaitlistEntryRepository waitlistEntryRepository;

    public WaitlistController(
            WaitlistEntryCreationService waitlistEntryCreationService,
            WaitlistEntryModificationService waitlistEntryModificationService,
            WaitlistEntryRemovalService waitlistEntryRemovalService,
            WaitlistEntryRepository waitlistEntryRepository) {
        this.waitlistEntryCreationService = waitlistEntryCreationService;
        this.waitlistEntryModificationService = waitlistEntryModificationService;
        this.waitlistEntryRemovalService = waitlistEntryRemovalService;
        this.waitlistEntryRepository = waitlistEntryRepository;
    }

    @PostMapping
    ResponseEntity<WaitlistEntryResponse> joinWaitlist(
            @Valid @RequestBody JoinWaitlistRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        WaitlistEntry waitlistEntry = waitlistEntryCreationService.createWaitlistEntry(
                authenticatedUser.getUserId(),
                request.currentAppointmentId(),
                request.appointmentTypeId(),
                request.earliestAppointmentDate(),
                request.latestAppointmentDate(),
                request.providerId(),
                request.preferredTimeOfDay());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WaitlistEntryResponse.from(waitlistEntry));
    }

    @GetMapping("/{id}")
    ResponseEntity<WaitlistEntryResponse> getWaitlistEntry(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        WaitlistEntry waitlistEntry = findWaitlistEntry(id);
        verifyOwnership(waitlistEntry, authenticatedUser.getUserId());
        return ResponseEntity.ok(WaitlistEntryResponse.from(waitlistEntry));
    }

    @PutMapping("/{id}")
    ResponseEntity<WaitlistEntryResponse> modifyWaitlistEntry(
            @PathVariable Long id,
            @Valid @RequestBody ModifyWaitlistRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        WaitlistEntry waitlistEntry = findWaitlistEntry(id);
        verifyOwnership(waitlistEntry, authenticatedUser.getUserId());

        WaitlistEntry modifiedEntry = waitlistEntryModificationService.modifyWaitlistEntry(
                id,
                request.newEarliestDate(),
                request.newLatestDate(),
                request.newPreferredProviderId(),
                request.newPreferredTimeOfDay(),
                authenticatedUser.getUserId());
        return ResponseEntity.ok(WaitlistEntryResponse.from(modifiedEntry));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<WaitlistEntryResponse> removeWaitlistEntry(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        WaitlistEntry waitlistEntry = findWaitlistEntry(id);
        switch (authenticatedUser.getRole()) {
            case PATIENT -> verifyOwnership(waitlistEntry, authenticatedUser.getUserId());
            case RECEPTIONIST, ADMIN -> {
                // Schedulers may remove any patient's entry under FR-WL-3.
            }
            case PROVIDER ->
                    throw new ProviderActionNotPermittedException(authenticatedUser.getRole());
        }

        WaitlistEntry removedEntry = waitlistEntryRemovalService.removeWaitlistEntry(
                id, authenticatedUser.getUserId());
        return ResponseEntity.ok(WaitlistEntryResponse.from(removedEntry));
    }

    private WaitlistEntry findWaitlistEntry(Long waitlistEntryId) {
        return waitlistEntryRepository.findById(waitlistEntryId)
                .orElseThrow(() -> new WaitlistEntryNotFoundException(waitlistEntryId));
    }

    private void verifyOwnership(WaitlistEntry waitlistEntry, Long effectivePatientId) {
        if (!effectivePatientId.equals(waitlistEntry.getPatientId())) {
            throw new WaitlistEntryOwnershipException(
                    effectivePatientId, waitlistEntry.getPatientId());
        }
    }
}
