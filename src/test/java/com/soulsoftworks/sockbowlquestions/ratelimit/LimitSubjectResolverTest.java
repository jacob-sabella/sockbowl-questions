package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LimitSubjectResolverTest {

    private static final String IP = "192.0.2.50";

    private final LimitSubjectResolver resolver = resolver(List.of("sockbowl-game-backend"));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "'admin,moderator,author,player', ADMIN",
            "'moderator,author,player', MODERATOR",
            "'author,player', AUTHOR",
            "'player', PLAYER",
            "'game:host,offline_access', PLAYER",
            "'', PLAYER",
            // Composite role order wins regardless of claim order.
            "'player,admin', ADMIN",
            "'player,author,moderator', MODERATOR",
    })
    void tierIsTheHighestCompositeRealmRole(String roles, Tier expected) {
        List<String> roleList = roles.isBlank() ? List.of() : Arrays.asList(roles.split(","));
        LimitSubject subject = resolver.forJwt(jwt("user-1", "sockbowl-ng", roleList), IP);

        assertThat(subject.tier()).isEqualTo(expected);
        assertThat(subject.sub()).isEqualTo("user-1");
        assertThat(subject.ip()).isEqualTo(IP);
    }

    @Test
    void tokenWithoutRealmAccessIsAPlayer() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-2")
                .claim("azp", "sockbowl-ng").issuedAt(Instant.now()).build();
        assertThat(resolver.forJwt(jwt, IP).tier()).isEqualTo(Tier.PLAYER);
    }

    @Test
    void serviceClientTokenIsServiceTierEvenWithAdminRoles() {
        LimitSubject subject = resolver.forJwt(jwt("svc-sub", "sockbowl-game-backend", List.of("admin")), IP);
        assertThat(subject.tier()).isEqualTo(Tier.SERVICE);
        assertThat(subject.sub()).isEqualTo("svc-sub");
    }

    @Test
    void anAdditionalListedServiceClientIsServiceTier() {
        LimitSubjectResolver withExtra = resolver(List.of("sockbowl-game-backend", "batch-importer"));
        assertThat(withExtra.forJwt(jwt("s2", "batch-importer", List.of()), IP).tier()).isEqualTo(Tier.SERVICE);
        assertThat(resolver.forJwt(jwt("s2", "batch-importer", List.of()), IP).tier()).isEqualTo(Tier.PLAYER);
    }

    @Test
    void clientIdClaimIsTheServiceFallbackWhenAzpIsAbsent() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("svc-3")
                .claim("client_id", "sockbowl-game-backend").issuedAt(Instant.now()).build();
        assertThat(resolver.forJwt(jwt, IP).tier()).isEqualTo(Tier.SERVICE);
    }

    @Test
    void anonymousCallersAreGuests() {
        assertThat(resolver.resolve(null, IP)).isEqualTo(LimitSubject.guest(IP));
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        LimitSubject subject = resolver.resolve(anonymous, IP);
        assertThat(subject.tier()).isEqualTo(Tier.GUEST);
        assertThat(subject.sub()).isNull();
        assertThat(subject.isAuthenticated()).isFalse();
    }

    @Test
    void resolvesTheCurrentRequestFromTheSecurityContextAndRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8:1:2:3:4:5:6");
        request.addHeader("X-Forwarded-For", "203.0.113.1");

        assertThat(resolver.resolve(request)).isEqualTo(LimitSubject.guest("2001:db8:1:2::/64"));

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("user-9", "sockbowl-ng", List.of("author")),
                        AuthorityUtils.createAuthorityList("ROLE_author")));
        LimitSubject subject = resolver.resolve(request);
        assertThat(subject).isEqualTo(new LimitSubject("user-9", "2001:db8:1:2::/64", Tier.AUTHOR));

        // An unauthenticated token object is never trusted for identity.
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("user-9", "sockbowl-ng", List.of("admin"))));
        assertThat(resolver.resolve(request).tier()).isEqualTo(Tier.GUEST);
    }

    @Test
    void fromRawIdentityParts() {
        assertThat(resolver.forIdentity(null, List.of("admin"), false, IP).tier()).isEqualTo(Tier.GUEST);
        assertThat(resolver.forIdentity(" ", List.of("admin"), false, IP).tier()).isEqualTo(Tier.GUEST);
        assertThat(resolver.forIdentity("a", List.of("admin"), false, IP).tier()).isEqualTo(Tier.ADMIN);
        assertThat(resolver.forIdentity("a", List.of("admin"), true, IP).tier()).isEqualTo(Tier.SERVICE);
    }

    @Test
    void rolePrefixedRealmRolesStillCount() {
        // questions' AuthenticatedUser strips a ROLE_ prefix from realm roles.
        assertThat(resolver.forJwt(jwt("user-5", "sockbowl-ng", List.of("ROLE_moderator")), IP).tier())
                .isEqualTo(Tier.MODERATOR);
    }

    @Test
    void theConfiguredServiceClientIdIsServiceTierEvenIfNotListed() {
        LimitSubjectResolver custom = new LimitSubjectResolver(new ClientIpResolver(), new RateLimitProperties(),
                "custom-backend");
        assertThat(custom.forJwt(jwt("s4", "custom-backend", List.of()), IP).tier()).isEqualTo(Tier.SERVICE);
        assertThat(custom.forJwt(jwt("s4", "sockbowl-game-backend", List.of()), IP).tier()).isEqualTo(Tier.PLAYER);
    }

    @Test
    void currentUsesTheBoundRequestAddressOrUnknown() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("user-7", "sockbowl-ng", List.of("author")),
                        AuthorityUtils.createAuthorityList("ROLE_author")));
        assertThat(resolver.current()).isEqualTo(new LimitSubject("user-7", ClientIpResolver.UNKNOWN, Tier.AUTHOR));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.77");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertThat(resolver.current()).isEqualTo(new LimitSubject("user-7", "198.51.100.77", Tier.AUTHOR));
            SecurityContextHolder.clearContext();
            assertThat(resolver.current()).isEqualTo(LimitSubject.guest("198.51.100.77"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void bucketKeysFollowTheKeyStrategy() {
        LimitSubject user = new LimitSubject("u1", IP, Tier.PLAYER);
        LimitSubject guest = LimitSubject.guest(IP);
        assertThat(user.keyFor(KeyBy.USER_OR_IP)).isEqualTo("u:u1");
        assertThat(user.keyFor(KeyBy.USER)).isEqualTo("u:u1");
        assertThat(user.keyFor(KeyBy.IP)).isEqualTo("ip:" + IP);
        assertThat(guest.keyFor(KeyBy.USER_OR_IP)).isEqualTo("ip:" + IP);
        assertThat(guest.keyFor(KeyBy.USER)).as("a guest never shares one global bucket").isEqualTo("ip:" + IP);
    }

    private static LimitSubjectResolver resolver(List<String> serviceClients) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setServiceClients(serviceClients);
        return new LimitSubjectResolver(new ClientIpResolver(), properties, "sockbowl-game-backend");
    }

    private static Jwt jwt(String sub, String azp, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(sub)
                .claim("azp", azp)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
