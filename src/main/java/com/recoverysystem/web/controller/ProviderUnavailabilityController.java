package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.exception.MissingProviderIdException;
import com.recoverysystem.exception.ProviderBlockOwnershipException;
import com.recoverysystem.exception.ProviderUnavailabilityNotFoundException;
import com.recoverysystem.repository.AppointmentRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.ProviderBlockActivationService;
import com.recoverysystem.service.ProviderBlockCancellationService;
import com.recoverysystem.service.ProviderBlockCreationService;
import com.recoverysystem.web.dto.ProviderUnavailabilityResponse;
import com.recoverysystem.web.dto.RequestProviderBlockRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/provider-unavailability")
public class ProviderUnavailabilityController {

    private final ProviderBlockCreationService providerBlockCreationService;
    private final ProviderBlockActivationService providerBlockActivationService;
    private final ProviderBlockCancellationService providerBlockCancellationService;
    private final ProviderRepository providerRepository;
    private final ProviderUnavailabilityRepository providerUnavailabilityRepository;
    private final AppointmentRepository appointmentRepository;

    public ProviderUnavailabilityController(
            ProviderBlockCreationService providerBlockCreationService,
            ProviderBlockActivationService providerBlockActivationService,
            ProviderBlockCancellationService providerBlockCancellationService,
            ProviderRepository providerRepository,
            ProviderUnavailabilityRepository providerUnavailabilityRepository,
            AppointmentRepository appointmentRepository) {
        this.providerBlockCreationService = providerBlockCreationService;
        this.providerBlockActivationService = providerBlockActivationService;
        this.providerBlockCancellationService = providerBlockCancellationService;
        this.providerRepository = providerRepository;
        this.providerUnavailabilityRepository = providerUnavailabilityRepository;
        this.appointmentRepository = appointmentRepository;
    }

    @PostMapping
    ResponseEntity<ProviderUnavailabilityResponse> requestProviderBlock(
            @Valid @RequestBody RequestProviderBlockRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        Long providerId = resolveProviderId(request.providerId(), authenticatedUser);
        ProviderUnavailability block = providerBlockCreationService.createProviderBlock(
                providerId,
                request.startAt(),
                request.endAt(),
                request.reason(),
                authenticatedUser.getUserId());

        List<Long> conflictingAppointmentIds =
                block.getStatus() == ProviderUnavailabilityStatus.PENDING
                        ? appointmentRepository.findScheduledOverlappingIds(
                                block.getProviderId(), block.getStartAt(), block.getEndAt())
                        : List.of();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProviderUnavailabilityResponse.from(block, conflictingAppointmentIds));
    }

    @PostMapping("/{id}/activate")
    ResponseEntity<ProviderUnavailabilityResponse> activateProviderBlock(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        ProviderUnavailability block = providerBlockActivationService.activateProviderBlock(
                id, authenticatedUser.getUserId());
        return ResponseEntity.ok(ProviderUnavailabilityResponse.from(block));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<ProviderUnavailabilityResponse> cancelProviderBlock(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        ProviderUnavailability block = providerUnavailabilityRepository.findById(id)
                .orElseThrow(() -> new ProviderUnavailabilityNotFoundException(id));
        switch (authenticatedUser.getRole()) {
            case PROVIDER -> {
                // Allowing a provider to withdraw their own PENDING request is an implementation
                // decision; neither governing source explicitly specifies cancellation rights.
                Long authenticatedProviderId = findProvider(authenticatedUser).getId();
                if (!authenticatedProviderId.equals(block.getProviderId())) {
                    throw new ProviderBlockOwnershipException(
                            id, authenticatedProviderId, block.getProviderId());
                }
            }
            case ADMIN -> {
                // Administrators may cancel any provider's PENDING request.
            }
            case PATIENT, RECEPTIONIST -> throw unexpectedAuthorizedRole(authenticatedUser);
        }

        ProviderUnavailability cancelled = providerBlockCancellationService.cancelPendingBlock(
                id, null, authenticatedUser.getUserId());
        return ResponseEntity.ok(ProviderUnavailabilityResponse.from(cancelled));
    }

    private Long resolveProviderId(
            Long requestedProviderId, AuthenticatedUser authenticatedUser) {
        return switch (authenticatedUser.getRole()) {
            case PROVIDER -> {
                Long authenticatedProviderId = findProvider(authenticatedUser).getId();
                if (requestedProviderId != null
                        && !requestedProviderId.equals(authenticatedProviderId)) {
                    throw ProviderBlockOwnershipException.forRequestedProvider(
                            authenticatedProviderId, requestedProviderId);
                }
                yield authenticatedProviderId;
            }
            case ADMIN -> {
                if (requestedProviderId == null) {
                    throw new MissingProviderIdException(authenticatedUser.getRole());
                }
                yield requestedProviderId;
            }
            case PATIENT, RECEPTIONIST -> throw unexpectedAuthorizedRole(authenticatedUser);
        };
    }

    private Provider findProvider(AuthenticatedUser authenticatedUser) {
        return providerRepository.findByUserId(authenticatedUser.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated PROVIDER user %d has no corresponding Provider row"
                                .formatted(authenticatedUser.getUserId())));
    }

    private IllegalStateException unexpectedAuthorizedRole(AuthenticatedUser authenticatedUser) {
        return new IllegalStateException(
                "Security admitted unsupported %s caller to provider-unavailability endpoint"
                        .formatted(authenticatedUser.getRole()));
    }
}
