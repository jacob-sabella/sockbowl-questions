package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D2 read matrix: caller (anonymous, player, non-owner author, owner, manage-any,
 * service token) × visibility (DRAFT, PUBLISHED, legacy null), plus auth disabled.
 */
class PacketReadPolicyTest {

    private static final String OWNER = "owner-sub";

    enum Caller { ANONYMOUS, NO_AUTH, PLAYER, NON_OWNER_AUTHOR, OWNER, MANAGE_ANY, SERVICE_TOKEN }

    private final PacketRepository repo = mock(PacketRepository.class);
    private final PacketReadPolicy authOn = new PacketReadPolicy(repo, true);
    private final PacketReadPolicy authOff = new PacketReadPolicy(repo, false);

    /** Expected (canSee, canReadFull) for each caller and visibility with auth on. */
    static Stream<Arguments> matrix() {
        List<Arguments> rows = new ArrayList<>();
        for (Caller c : Caller.values()) {
            boolean full = switch (c) {
                case OWNER, MANAGE_ANY, SERVICE_TOKEN -> true;
                default -> false;
            };
            // DRAFT: only full readers see it at all.
            rows.add(Arguments.of(c, PacketVisibility.DRAFT, full, full));
            // PUBLISHED and legacy (null): everyone sees it; only full readers get answers.
            rows.add(Arguments.of(c, PacketVisibility.PUBLISHED, true, full));
            rows.add(Arguments.of(c, null, true, full));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} x {1}: canSee={2}, canReadFull={3}")
    @MethodSource("matrix")
    void authOnMatrix(Caller caller, PacketVisibility visibility, boolean canSee, boolean canReadFull) {
        Packet packet = packet(visibility, OWNER);
        Authentication auth = auth(caller);

        assertThat(authOn.canSee(auth, packet)).as("canSee").isEqualTo(canSee);
        assertThat(authOn.canReadFull(auth, packet)).as("canReadFull").isEqualTo(canReadFull);
    }

    @ParameterizedTest(name = "auth off: {0}")
    @MethodSource("allCallersAndVisibilities")
    void authDisabledGivesEveryoneFullRead(Caller caller, PacketVisibility visibility) {
        Packet packet = packet(visibility, OWNER);
        Authentication auth = auth(caller);

        assertThat(authOff.canSee(auth, packet)).isTrue();
        assertThat(authOff.canReadFull(auth, packet)).isTrue();
        assertThat(authOff.canReadEveryPacket(auth)).isTrue();
    }

    static Stream<Arguments> allCallersAndVisibilities() {
        List<Arguments> rows = new ArrayList<>();
        for (Caller c : Caller.values()) {
            for (PacketVisibility v : new PacketVisibility[]{PacketVisibility.DRAFT, PacketVisibility.PUBLISHED, null}) {
                rows.add(Arguments.of(c, v));
            }
        }
        return rows.stream();
    }

    @Test
    void ownerlessDraftIsHiddenFromAuthorsButNotFromFullReaders() {
        Packet ownerless = packet(PacketVisibility.DRAFT, null);

        assertThat(authOn.canSee(auth(Caller.NON_OWNER_AUTHOR), ownerless)).isFalse();
        assertThat(authOn.canSee(auth(Caller.ANONYMOUS), ownerless)).isFalse();
        assertThat(authOn.canReadFull(auth(Caller.MANAGE_ANY), ownerless)).isTrue();
        assertThat(authOn.canReadFull(auth(Caller.SERVICE_TOKEN), ownerless)).isTrue();
    }

    @Test
    void anonymousPrincipalNamedLikeTheOwnerIsNotTheOwner() {
        Packet packet = packet(PacketVisibility.DRAFT, "anonymousUser");

        assertThat(authOn.canSee(auth(Caller.ANONYMOUS), packet)).isFalse();
        assertThat(authOn.callerId(auth(Caller.ANONYMOUS))).isNull();
    }

    @Test
    void canReadEveryPacketOnlyForServiceTokenAndManageAny() {
        for (Caller c : Caller.values()) {
            boolean expected = c == Caller.MANAGE_ANY || c == Caller.SERVICE_TOKEN;
            assertThat(authOn.canReadEveryPacket(auth(c))).as(c.name()).isEqualTo(expected);
        }
    }

    @Test
    void missingPacketIsNeverVisible() {
        assertThat(authOn.canSee(auth(Caller.MANAGE_ANY), (Packet) null)).isFalse();
        assertThat(authOn.canReadFull(auth(Caller.MANAGE_ANY), (Packet) null)).isFalse();
        assertThat(authOff.canSee(auth(Caller.NO_AUTH), (Packet) null)).isFalse();
    }

    @Test
    void idOverloadsLoadThenDelegate() {
        when(repo.findById("draft")).thenReturn(Optional.of(packet(PacketVisibility.DRAFT, OWNER)));
        when(repo.findById("pub")).thenReturn(Optional.of(packet(PacketVisibility.PUBLISHED, OWNER)));
        when(repo.findById("missing")).thenReturn(Optional.empty());

        assertThat(authOn.canSee(auth(Caller.PLAYER), "draft")).isFalse();
        assertThat(authOn.canSee(auth(Caller.OWNER), "draft")).isTrue();
        assertThat(authOn.canSee(auth(Caller.PLAYER), "pub")).isTrue();
        assertThat(authOn.canReadFull(auth(Caller.PLAYER), "pub")).isFalse();
        assertThat(authOn.canReadFull(auth(Caller.SERVICE_TOKEN), "draft")).isTrue();
        // Unknown id: false for everyone, including full readers and auth off.
        assertThat(authOn.canSee(auth(Caller.MANAGE_ANY), "missing")).isFalse();
        assertThat(authOn.canReadFull(auth(Caller.SERVICE_TOKEN), "missing")).isFalse();
        assertThat(authOff.canReadFull(auth(Caller.NO_AUTH), "missing")).isFalse();
        assertThat(authOn.canSee(auth(Caller.MANAGE_ANY), (String) null)).isFalse();
    }

    @Test
    void publiclyReadableVisibilitiesComeFromTheEnum() {
        List<String> expected = Arrays.stream(PacketVisibility.values())
                .filter(PacketVisibility::isPubliclyReadable).map(Enum::name).toList();

        assertThat(authOn.publiclyReadableVisibilities()).containsExactlyElementsOf(expected)
                .contains("PUBLISHED").doesNotContain("DRAFT");
        assertThat(authOn.legacyVisibility()).isEqualTo("PUBLISHED");
    }

    private static Packet packet(PacketVisibility visibility, String ownerId) {
        return Packet.builder().id("p").name("P").ownerId(ownerId).visibility(visibility).build();
    }

    private static Authentication auth(Caller caller) {
        return switch (caller) {
            case NO_AUTH -> null;
            case ANONYMOUS -> new AnonymousAuthenticationToken("key", "anonymousUser",
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
            case PLAYER -> jwt("player-sub", "game:join", "game:host");
            case NON_OWNER_AUTHOR -> jwt("author2-sub", "packet:create", "packet:update", "packet:delete",
                    "packet:read");
            case OWNER -> jwt(OWNER, "packet:create", "packet:update", "packet:delete", "packet:read");
            case MANAGE_ANY -> jwt("admin-sub", "packet:create", "packet:update", "packet:manage-any");
            // The service token carries only packet:read and packet:read-answers.
            case SERVICE_TOKEN -> jwt("service-account-sub", "packet:read", "packet:read-answers");
        };
    }

    private static Authentication jwt(String sub, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), sub);
    }
}
