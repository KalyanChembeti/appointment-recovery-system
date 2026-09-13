package com.recoverysystem.web.security;

import com.recoverysystem.domain.enums.UserRole;
import com.recoverysystem.exception.ProviderActionNotPermittedException;
import com.recoverysystem.security.AuthenticatedUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class AppointmentAccessDeniedHandler implements AccessDeniedHandler {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public AppointmentAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver")
            HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser caller
                && caller.getRole() == UserRole.PROVIDER) {
            handlerExceptionResolver.resolveException(
                    request,
                    response,
                    null,
                    new ProviderActionNotPermittedException(caller.getRole()));
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
