package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.Provider;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.service.ProviderCreationService;
import com.recoverysystem.web.dto.CreateProviderRequest;
import com.recoverysystem.web.dto.ProviderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderCreationService providerCreationService;

    public ProviderController(ProviderCreationService providerCreationService) {
        this.providerCreationService = providerCreationService;
    }

    @PostMapping
    ResponseEntity<ProviderResponse> createProvider(
            @Valid @RequestBody CreateProviderRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        // This plural path is an explicit implementation choice; no locked catalog entry exists.
        Provider provider = providerCreationService.createProvider(
                request.userId(),
                request.specialtyId(),
                request.licenseNumber(),
                request.qualifications(),
                authenticatedUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProviderResponse.from(provider));
    }
}
