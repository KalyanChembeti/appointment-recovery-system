package com.recoverysystem.service;

import com.recoverysystem.repository.AppointmentTypeRepository;
import com.recoverysystem.repository.ProviderRepository;
import com.recoverysystem.repository.SpecialtyRepository;
import com.recoverysystem.web.dto.AppointmentTypeResponse;
import com.recoverysystem.web.dto.ProviderListResponse;
import com.recoverysystem.web.dto.SpecialtyResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReferenceDataQueryService {

    private final SpecialtyRepository specialtyRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final ProviderRepository providerRepository;

    public ReferenceDataQueryService(
            SpecialtyRepository specialtyRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            ProviderRepository providerRepository) {
        this.specialtyRepository = specialtyRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.providerRepository = providerRepository;
    }

    public List<SpecialtyResponse> findSpecialties() {
        return specialtyRepository.findAllOrdered().stream()
                .map(SpecialtyResponse::from)
                .toList();
    }

    public List<AppointmentTypeResponse> findAppointmentTypes(Long specialtyId) {
        var appointmentTypes = specialtyId == null
                ? appointmentTypeRepository.findAllActiveOrdered()
                : appointmentTypeRepository.findActiveBySpecialtyIdOrdered(specialtyId);
        return appointmentTypes.stream()
                .map(AppointmentTypeResponse::from)
                .toList();
    }

    public List<ProviderListResponse> findProviders(Long specialtyId) {
        return specialtyId == null
                ? providerRepository.findAllProviderListResponses()
                : providerRepository.findProviderListResponsesBySpecialtyId(specialtyId);
    }
}
