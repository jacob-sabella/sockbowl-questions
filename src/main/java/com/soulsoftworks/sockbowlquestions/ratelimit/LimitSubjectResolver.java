package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the {@link LimitSubject} for a caller (plan m4-limits section 2.1).
 *
 * <p>This is the one class of the limiter core that is <b>not</b> a verbatim
 * copy of sockbowl-game's: the two services have different identity types.
 * Identity and realm roles come from questions' {@link AuthenticatedUser}
 * (which reads {@code realm_access.roles}); this class maps them to a
 * {@link Tier}. A token is the SERVICE tier when its {@code azp} (or
 * {@code client_id}) equals {@code sockbowl.auth.service-client-id} (default
 * {@value #DEFAULT_SERVICE_CLIENT_ID}, the same key and default game uses) or
 * is listed in {@code sockbowl.ratelimit.service-clients}.
 */
@Component
public class LimitSubjectResolver {

    /** Default {@code sockbowl.auth.service-client-id}: the game backend's client-credentials client. */
    public static final String DEFAULT_SERVICE_CLIENT_ID = "sockbowl-game-backend";

    private final ClientIpResolver clientIpResolver;
    private final String serviceClientId;
    private final Set<String> serviceClients;

    public LimitSubjectResolver(
            ClientIpResolver clientIpResolver,
            RateLimitProperties properties,
            @Value("${sockbowl.auth.service-client-id:" + DEFAULT_SERVICE_CLIENT_ID + "}")
            String serviceClientId) {
        this.clientIpResolver = clientIpResolver;
        this.serviceClientId = serviceClientId;
        this.serviceClients = new LinkedHashSet<>(properties.getServiceClients());
    }

    /** The subject of the current servlet request (SecurityContext + remote address). */
    public LimitSubject resolve(HttpServletRequest request) {
        return resolve(SecurityContextHolder.getContext().getAuthentication(), clientIpResolver.resolve(request));
    }

    /**
     * The subject of the current thread, for service-layer guards (AI generation,
     * content quotas) that run below the controller: the {@code SecurityContext}
     * plus the address of the servlet request bound to this thread, or
     * {@link ClientIpResolver#UNKNOWN} when none is bound (e.g. a GraphQL data
     * fetcher on an executor thread; use the subject the GraphQL interceptor put in
     * the {@code GraphQLContext} there when the address matters).
     */
    public LimitSubject current() {
        String ip = ClientIpResolver.UNKNOWN;
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            ip = clientIpResolver.resolve(servlet.getRequest());
        }
        return resolve(SecurityContextHolder.getContext().getAuthentication(), ip);
    }

    public LimitSubject resolve(Authentication authentication, String normalizedIp) {
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return LimitSubject.guest(normalizedIp);
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return forJwt(jwt, normalizedIp);
        }
        return LimitSubject.guest(normalizedIp);
    }

    public LimitSubject forJwt(Jwt jwt, String normalizedIp) {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt);
        boolean service = isServiceToken(jwt);
        return forIdentity(user.keycloakId(), user.authorities(), service, normalizedIp);
    }

    /**
     * From already-extracted identity parts. A {@code null} subject is a guest.
     */
    public LimitSubject forIdentity(String sub, Collection<String> roles, boolean service, String normalizedIp) {
        if (sub == null || sub.isBlank()) {
            return LimitSubject.guest(normalizedIp);
        }
        return new LimitSubject(sub, normalizedIp, service ? Tier.SERVICE : tierOf(roles));
    }

    /** Highest composite realm role: admin &gt; moderator &gt; author &gt; player (default PLAYER). */
    public static Tier tierOf(Collection<String> roles) {
        if (roles == null) {
            return Tier.PLAYER;
        }
        if (roles.contains("admin")) {
            return Tier.ADMIN;
        }
        if (roles.contains("moderator")) {
            return Tier.MODERATOR;
        }
        if (roles.contains("author")) {
            return Tier.AUTHOR;
        }
        return Tier.PLAYER;
    }

    private boolean isServiceToken(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        if (azp == null) {
            azp = jwt.getClaimAsString("client_id");
        }
        if (azp == null) {
            return false;
        }
        return azp.equals(serviceClientId) || serviceClients.contains(azp);
    }
}
