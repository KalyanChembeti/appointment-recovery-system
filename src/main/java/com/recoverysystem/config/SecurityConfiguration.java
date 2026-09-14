package com.recoverysystem.config;

import com.recoverysystem.security.AuthenticatedUserDetailsService;
import com.recoverysystem.web.security.AppointmentAccessDeniedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    DaoAuthenticationProvider daoAuthenticationProvider(
            AuthenticatedUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AppointmentAccessDeniedHandler appointmentAccessDeniedHandler) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(
                        CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .sessionManagement(session -> session
                        // Spring Security recommends changeSessionId; keep it explicit here.
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // This is the first real per-role endpoint rule. The permitAll fallback
                        // remains deliberate for paths awaiting their own controller stage.
                        .requestMatchers(HttpMethod.GET, "/api/appointments")
                        .hasAnyRole("PATIENT", "PROVIDER", "RECEPTIONIST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/appointments")
                        .hasAnyRole("PATIENT", "RECEPTIONIST", "ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/appointments/{id}/cancel",
                                "/api/appointments/{id}/reschedule")
                        .hasAnyRole("PATIENT", "RECEPTIONIST", "ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/appointments/{id}/complete",
                                "/api/appointments/{id}/no-show")
                        .hasAnyRole("RECEPTIONIST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/waitlist")
                        .hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/waitlist/{id}")
                        .hasRole("PATIENT")
                        .requestMatchers(HttpMethod.PUT, "/api/waitlist/{id}")
                        .hasRole("PATIENT")
                        .requestMatchers(HttpMethod.DELETE, "/api/waitlist/{id}")
                        .hasAnyRole("PATIENT", "RECEPTIONIST", "ADMIN")
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .defaultAccessDeniedHandlerFor(
                                appointmentAccessDeniedHandler,
                                new OrRequestMatcher(
                                        new AntPathRequestMatcher(
                                                "/api/appointments", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/appointments/*/cancel", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/appointments/*/reschedule", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/appointments/*/complete", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/appointments/*/no-show", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist/*", "GET"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist/*", "PUT"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist/*", "DELETE"))));

        return http.build();
    }
}
