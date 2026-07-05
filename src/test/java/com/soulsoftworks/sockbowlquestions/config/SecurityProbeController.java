package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway controller used only by {@link SecurityConfigTest} to exercise
 * {@link SecurityConfig}'s wiring (authentication + per-authority
 * {@code @PreAuthorize}) via a {@code @WebMvcTest} slice.
 *
 * <p>This must be a top-level class (not nested inside the test class):
 * Spring Boot's {@code TestTypeExcludeFilter} excludes classes whose
 * enclosing class is a JUnit test class from component scanning, so a
 * nested {@code @RestController} inside {@code SecurityConfigTest} would
 * silently fail to register as a bean, and every request would 404 instead
 * of exercising the security filter chain.
 */
@RestController
public class SecurityProbeController {

    @GetMapping("/probe/read")
    @PreAuthorize("hasAuthority('packet:read')")
    public String read() {
        return "ok";
    }

    @GetMapping("/probe/create")
    @PreAuthorize("hasAuthority('packet:create')")
    public String create() {
        return "ok";
    }
}
