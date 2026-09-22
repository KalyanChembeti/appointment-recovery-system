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
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
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
        CsrfTokenRequestAttributeHandler requestHandler =
                new CsrfTokenRequestAttributeHandler();
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // A JavaScript SPA reads the raw cookie and echoes it in a header.
                        // Opting out of Spring Security 6's default BREACH protection is an
                        // accepted trade-off here because that protection targets secrets
                        // embedded in compressible HTML responses, which this API does not do.
                        .csrfTokenRequestHandler(requestHandler))
                .sessionManagement(session -> session
                        // Spring Security recommends changeSessionId; keep it explicit here.
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me")
                        .hasAnyRole("PATIENT", "PROVIDER", "RECEPTIONIST", "ADMIN")
                        // This is the first real per-role endpoint rule. The permitAll fallback
                        // remains deliberate for paths awaiting their own controller stage.
                        .requestMatchers(HttpMethod.GET, "/api/appointments")
                        .hasAnyRole("PATIENT", "PROVIDER", "RECEPTIONIST", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/appointments/{id}")
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
                                "/api/appointments/{id}/mark-no-show")
                        .hasAnyRole("RECEPTIONIST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/waitlist")
                        .hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/waitlist/{id}")
                        .hasRole("PATIENT")
                        .requestMatchers(HttpMethod.PUT, "/api/waitlist/{id}")
                        .hasRole("PATIENT")
                        .requestMatchers(HttpMethod.DELETE, "/api/waitlist/{id}")
                        .hasAnyRole("PATIENT", "RECEPTIONIST", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/slot-offers")
                        .hasRole("PATIENT")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/slot-offers/{id}/accept",
                                "/api/slot-offers/{id}/decline")
                        .hasAnyRole("PATIENT", "RECEPTIONIST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/providers")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/provider-unavailability")
                        .hasAnyRole("PROVIDER", "ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/provider-unavailability/{id}/activate")
                        .hasRole("ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/provider-unavailability/{id}/cancel")
                        .hasAnyRole("PROVIDER", "ADMIN")
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
                                                "/api/appointments/*/mark-no-show", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist/*", "GET"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist/*", "PUT"),
                                        new AntPathRequestMatcher(
                                                "/api/waitlist/*", "DELETE"),
                                        new AntPathRequestMatcher(
                                                "/api/slot-offers/*/accept", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/slot-offers/*/decline", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/providers", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/provider-unavailability", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/provider-unavailability/*/activate", "POST"),
                                        new AntPathRequestMatcher(
                                                "/api/provider-unavailability/*/cancel", "POST"))));

        return http.build();
    }
}
