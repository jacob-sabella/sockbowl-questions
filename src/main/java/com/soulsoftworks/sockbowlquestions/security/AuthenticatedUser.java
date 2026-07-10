package com.soulsoftworks.sockbowlquestions.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Framework-decoupled view of the caller, derived from the resource-server
 * {@link Jwt} when present. Mirrors {@code sockbowl-game}'s {@code AuthenticatedUser}.
 *
 * <p>When {@code sockbowl.auth.enabled=false} ({@link com.soulsoftworks.sockbowlquestions.config.NoSecurityConfig})
 * no JWT is ever populated, so callers always resolve to {@link #guest()} — packets
 * created in that mode are ownerless, matching pre-auth behavior exactly.
 */
public record AuthenticatedUser(String keycloakId, String username, Set<String> authorities, boolean anonymous) {

    public static AuthenticatedUser guest() {
        return new AuthenticatedUser(null, null, Set.of(), true);
    }

    public static AuthenticatedUser fromJwt(Jwt jwt) {
        if (jwt == null) {
            return guest();
        }
        return new AuthenticatedUser(jwt.getSubject(), jwt.getClaimAsString("preferred_username"),
                extractRealmRoles(jwt), false);
    }

    public boolean isAuthenticated() {
        return !anonymous && keycloakId != null;
    }

    private static Set<String> extractRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> m) || !(m.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role != null) {
                String s = role.toString();
                result.add(s.startsWith("ROLE_") ? s.substring(5) : s);
            }
        }
        return result;
    }
}
