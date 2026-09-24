package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.Appointment;
import com.recoverysystem.domain.entity.SlotOffer;
import com.recoverysystem.domain.entity.WaitlistEntry;
import com.recoverysystem.exception.OfferAcceptanceOwnershipException;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.exception.SlotOfferNotFoundException;
import com.recoverysystem.exception.WaitlistEntryNotFoundException;
import com.recoverysystem.repository.SlotOfferRepository;
import com.recoverysystem.repository.WaitlistEntryRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.OfferAcceptanceOrchestrator;
import com.recoverysystem.service.SlotOfferDeclineService;
import com.recoverysystem.web.dto.AcceptOfferRequest;
import com.recoverysystem.web.dto.AcceptOfferResponse;
import com.recoverysystem.web.dto.SlotOfferResponse;
import com.recoverysystem.web.security.EffectivePatientIdResolver;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/slot-offers")
public class OfferController {

    private final OfferAcceptanceOrchestrator offerAcceptanceOrchestrator;
    private final SlotOfferDeclineService slotOfferDeclineService;
    private final SlotOfferRepository slotOfferRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final EffectivePatientIdResolver effectivePatientIdResolver;

    public OfferController(
            OfferAcceptanceOrchestrator offerAcceptanceOrchestrator,
            SlotOfferDeclineService slotOfferDeclineService,
            SlotOfferRepository slotOfferRepository,
            WaitlistEntryRepository waitlistEntryRepository,
            EffectivePatientIdResolver effectivePatientIdResolver) {
        this.offerAcceptanceOrchestrator = offerAcceptanceOrchestrator;
        this.slotOfferDeclineService = slotOfferDeclineService;
        this.slotOfferRepository = slotOfferRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.effectivePatientIdResolver = effectivePatientIdResolver;
    }

    @GetMapping
    ResponseEntity<List<SlotOfferResponse>> listOffers(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        List<SlotOfferResponse> response = slotOfferRepository
                .findAllForPatient(authenticatedUser.getUserId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/accept")
    ResponseEntity<AcceptOfferResponse> acceptOffer(
            @PathVariable Long id,
            @Valid @RequestBody AcceptOfferRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Long effectivePatientId = effectivePatientIdResolver.resolve(
                request.patientId(), authenticatedUser);
        Appointment appointment = offerAcceptanceOrchestrator.acceptOffer(
                id, effectivePatientId, authenticatedUser.getUserId());
        return ResponseEntity.ok(AcceptOfferResponse.from(id, appointment));
    }

    @PostMapping("/{id}/decline")
    ResponseEntity<SlotOfferResponse> declineOffer(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        SlotOffer slotOffer = findSlotOffer(id);
        WaitlistEntry waitlistEntry = waitlistEntryRepository
                .findById(slotOffer.getWaitlistEntryId())
                .orElseThrow(() -> new WaitlistEntryNotFoundException(
                        slotOffer.getWaitlistEntryId()));

        switch (authenticatedUser.getRole()) {
            case PATIENT -> verifyOfferOwnership(
                    slotOffer, waitlistEntry, authenticatedUser.getUserId());
            case RECEPTIONIST, ADMIN -> {
                // Staff roles may decline any patient's offer.
            }
            case PROVIDER ->
                    throw new ProviderActionNotPermittedException(authenticatedUser.getRole());
        }

        slotOfferDeclineService.declineOffer(id, authenticatedUser.getUserId());
        SlotOfferResponse response = slotOfferRepository.findResponseById(id)
                .orElseThrow(() -> new SlotOfferNotFoundException(id));
        return ResponseEntity.ok(response);
    }

    private SlotOffer findSlotOffer(Long slotOfferId) {
        return slotOfferRepository.findById(slotOfferId)
                .orElseThrow(() -> new SlotOfferNotFoundException(slotOfferId));
    }

    private void verifyOfferOwnership(
            SlotOffer slotOffer, WaitlistEntry waitlistEntry, Long effectivePatientId) {
        if (!effectivePatientId.equals(waitlistEntry.getPatientId())) {
            throw new OfferAcceptanceOwnershipException(
                    slotOffer.getId(), effectivePatientId, waitlistEntry.getPatientId());
        }
    }
}
