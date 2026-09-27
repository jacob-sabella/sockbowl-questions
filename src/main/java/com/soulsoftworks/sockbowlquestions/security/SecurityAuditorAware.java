package com.soulsoftworks.sockbowlquestions.security;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves who to blame for a Neo4j write (D13, M4-PV-01): the {@code sub} of the
 * authenticated caller, {@code "service:<sub>"} for the game backend's service token
 * (recognized by the {@code packet:read-answers} authority — the de-facto service-tier
 * marker used throughout this codebase; a formal SERVICE tier/{@code azp} concept is
 * introduced later and is out of scope for this work package), or {@code "anonymous"}
 * for every other caller (guests, and every request when {@code sockbowl.auth.enabled=false}).
 *
 * <p>Wired as the {@code auditorAwareRef} for {@code @EnableNeo4jAuditing}
 * ({@link com.soulsoftworks.sockbowlquestions.config.Neo4jAuditingConfig}), so it drives
 * {@code @CreatedBy}/{@code @LastModifiedBy} on every SDN-managed save. Raw-Cypher write
 * paths (only {@code PacketRepository.batchCreatePacket}) don't go through SDN's save
 * pipeline and must call {@link #currentAuditorValue()} directly to stamp the same value.
 */
@Component("securityAuditorAware")
public class SecurityAuditorAware implements AuditorAware<String> {

    static final String READ_ANSWERS_AUTHORITY = "packet:read-answers";
    static final String ANONYMOUS = "anonymous";

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    @Override
    public Optional<String> getCurrentAuditor() {
        return Optional.of(resolve(SecurityContextHolder.getContext().getAuthentication()));
    }

    /** The value {@link #getCurrentAuditor()} would stamp, for callers that write outside SDN's save pipeline. */
    public String currentAuditorValue() {
        return resolve(SecurityContextHolder.getContext().getAuthentication());
    }

    private String resolve(Authentication auth) {
        if (!isRealUser(auth)) {
            return ANONYMOUS;
        }
        if (hasAuthority(auth, READ_ANSWERS_AUTHORITY)) {
            return "service:" + auth.getName();
        }
        return auth.getName();
    }

    private static boolean isRealUser(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !TRUST_RESOLVER.isAnonymous(auth);
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
