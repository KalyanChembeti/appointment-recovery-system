package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.security.AuthenticatedUserDetailsService;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuthenticationMechanismTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String RAW_PASSWORD = "correct-horse-battery-staple";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private AuthenticatedUserDetailsService authenticatedUserDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearUsers() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, slot_offer, recovery_job, "
                + "waitlist_entry, appointment, provider_schedule, provider_unavailability, "
                + "provider, appointment_type, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void correctEmailAndPasswordAuthenticateWithDomainUserId() {
        User user = saveUser(UserRole.PATIENT);

        Authentication authentication = authenticate(user.getEmail(), RAW_PASSWORD);

        assertTrue(authentication.isAuthenticated());
        AuthenticatedUser principal =
                assertInstanceOf(AuthenticatedUser.class, authentication.getPrincipal());
        assertEquals(user.getId(), principal.getUserId());
    }

    @Test
    void wrongPasswordAndUnknownEmailProduceTheSameBadCredentialsException() {
        User user = saveUser(UserRole.PATIENT);

        BadCredentialsException wrongPassword = assertThrows(
                BadCredentialsException.class,
                () -> authenticate(user.getEmail(), "wrong-password"));
        BadCredentialsException unknownEmail = assertThrows(
                BadCredentialsException.class,
                () -> authenticate("missing-" + SEQUENCE.incrementAndGet() + "@example.com",
                        RAW_PASSWORD));

        assertEquals(wrongPassword.getClass(), unknownEmail.getClass());
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void eachRoleMapsToExactlyOneRolePrefixedAuthority(UserRole role) {
        User user = saveUser(role);

        Authentication authentication = authenticate(user.getEmail(), RAW_PASSWORD);
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertEquals(List.of("ROLE_" + role.name()), authorities);
    }

    @Test
    void freshlyLoadedUserHasAllAccountStatusFlagsEnabled() {
        User user = saveUser(UserRole.PROVIDER);

        AuthenticatedUser loaded = assertInstanceOf(
                AuthenticatedUser.class,
                authenticatedUserDetailsService.loadUserByUsername(user.getEmail()));

        assertTrue(loaded.isEnabled());
        assertTrue(loaded.isAccountNonExpired());
        assertTrue(loaded.isAccountNonLocked());
        assertTrue(loaded.isCredentialsNonExpired());
    }

    private Authentication authenticate(String email, String rawPassword) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, rawPassword));
    }

    private User saveUser(UserRole role) {
        long number = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + number + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
        user.setRole(role);
        user.setDisplayName("Authentication test " + role.name().toLowerCase());
        return userRepository.saveAndFlush(user);
    }
}
