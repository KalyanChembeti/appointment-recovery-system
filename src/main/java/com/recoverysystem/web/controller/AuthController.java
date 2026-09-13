package com.recoverysystem.web.controller;

import com.recoverysystem.domain.entity.User;
import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.exception.DuplicateEmailException;
import com.recoverysystem.repository.UserRepository;
import com.recoverysystem.security.AuthenticatedUser;
import com.recoverysystem.web.dto.LoginRequest;
import com.recoverysystem.web.dto.LoginResponse;
import com.recoverysystem.web.dto.LogoutResponse;
import com.recoverysystem.web.dto.RegisterRequest;
import com.recoverysystem.web.dto.RegisterResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (userRepository.findByEmail(requestBody.email()).isPresent()) {
            throw new DuplicateEmailException(requestBody.email());
        }

        User user = new User();
        user.setEmail(requestBody.email());
        user.setPasswordHash(passwordEncoder.encode(requestBody.password()));
        user.setRole(UserRole.PATIENT);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException(requestBody.email());
        }

        AuthenticatedUser authenticatedUser = establishSession(
                requestBody.email(), requestBody.password(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(
                        authenticatedUser.getUserId(),
                        authenticatedUser.getUsername(),
                        authenticatedUser.getRole()));
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthenticatedUser authenticatedUser = establishSession(
                requestBody.email(), requestBody.password(), request, response);
        return new LoginResponse(authenticatedUser.getUserId(), true);
    }

    @PostMapping("/logout")
    public LogoutResponse logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return new LogoutResponse("logged out");
    }

    private AuthenticatedUser establishSession(
            String email,
            String rawPassword,
            HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, rawPassword));
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return (AuthenticatedUser) authentication.getPrincipal();
    }

}
