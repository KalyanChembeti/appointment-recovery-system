package com.recoverysystem.service;

import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.web.dto.PatientSearchResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PatientSearchQueryService {

    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final char LIKE_ESCAPE_CHARACTER = '!';

    private final UserRepository userRepository;

    public PatientSearchQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<PatientSearchResponse> searchPatients(String query) {
        String escapedQuery = escapeLikePattern(query.toLowerCase(Locale.ROOT));
        String containsPattern = "%" + escapedQuery + "%";
        return userRepository.searchByRoleAndEmailOrDisplayName(
                UserRole.PATIENT,
                containsPattern,
                PageRequest.of(0, SEARCH_RESULT_LIMIT));
    }

    private String escapeLikePattern(String value) {
        String escape = Character.toString(LIKE_ESCAPE_CHARACTER);
        return value.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }
}
