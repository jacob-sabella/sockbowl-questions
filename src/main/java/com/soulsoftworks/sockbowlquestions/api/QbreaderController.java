package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import org.springframework.beans.factory.annotation.Value;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST facade for generating a packet from the local Neo4j question bank. Returns
 * only the new packet's id + name; callers fetch the full packet via the existing
 * GraphQL {@code getPacketById} and then use it as a match packet.
 *
 * <p>The {@code /api/qbreader} path is retained purely for backward compatibility —
 * there is no longer any qbreader.org call; questions come from the local bank.
 */
@RestController
@RequestMapping("/api/qbreader")
public class QbreaderController {

    /** Tossups/bonuses used when the request leaves a count unset (then clamped to the max). */
    static final int DEFAULT_COUNT = 20;

    /** Callers with this authority get an owned DRAFT packet; everyone else an EPHEMERAL one (D15). */
    static final String PACKET_CREATE = "packet:create";

    private final QbreaderImportService importService;
    private final int maxTossups;
    private final int maxBonuses;
    private final boolean authEnabled;

    public QbreaderController(QbreaderImportService importService,
                              @Value("${sockbowl.import.max-tossups:30}") int maxTossups,
                              @Value("${sockbowl.import.max-bonuses:30}") int maxBonuses,
                              @Value("${sockbowl.auth.enabled:false}") boolean authEnabled) {
        this.importService = importService;
        this.maxTossups = maxTossups;
        this.maxBonuses = maxBonuses;
        this.authEnabled = authEnabled;
    }

    /**
     * Generate and persist a packet matching the given filters, optionally excluding
     * questions the caller has already seen (their qbreader ids). Returns the qbreader
     * ids actually used so the caller can record them per user.
     *
     * <p>Who gets what (D3 as amended by D15), with auth enabled:
     * <ul>
     *   <li>a caller holding {@code packet:create} gets an owned
     *       {@link PacketVisibility#DRAFT} packet, as before;</li>
     *   <li>anyone else (anonymous guests, players, a token without
     *       {@code packet:create}) gets an ownerless {@link PacketVisibility#EPHEMERAL}
     *       packet: game-only, unlisted, not editable, and deleted after
     *       {@code sockbowl.packet.ephemeral-ttl}. The game loads it with its service
     *       token through {@code SetMatchPacket}.</li>
     * </ul>
     * With auth disabled nothing changes from before M2: an ownerless DRAFT packet that
     * anyone may read. A bearer, when sent, is still validated (an invalid one is a 401).
     *
     * <p>Either way the counts are bounded: {@code tossupCount} below 1 (or
     * {@code bonusCount} below 0; a tossup-only packet is allowed, as the Generate UI
     * offers) is a 400, an unset value defaults to {@value #DEFAULT_COUNT}, and anything
     * above {@code sockbowl.import.max-tossups} / {@code max-bonuses} (default 30) is
     * clamped down to it. M4 adds a per-IP rate limit.
     */
    @PostMapping("/import-random")
    public ImportResult importRandom(@RequestBody RandomRequest request, @AuthenticationPrincipal Jwt jwt) {
        int tossupCount = clampCount("tossupCount", request.tossupCount(), 1, maxTossups);
        int bonusCount = clampCount("bonusCount", request.bonusCount(), 0, maxBonuses);
        boolean ephemeral = authEnabled && !hasAuthority(SecurityContextHolder.getContext().getAuthentication(),
                PACKET_CREATE);
        // guest() (no owner) when auth is disabled; an EPHEMERAL packet never has an owner.
        AuthenticatedUser user = ephemeral ? AuthenticatedUser.guest() : AuthenticatedUser.fromJwt(jwt);
        QbRandomFilter filter = new QbRandomFilter(
                request.categories(),
                request.subcategories(),
                request.alternateSubcategories(),
                request.difficulties(),
                request.minYear(),
                request.maxYear(),
                request.standardOnly());
        ImportOutcome outcome = importService.importRandomPacket(
                filter,
                tossupCount,
                bonusCount,
                request.name(),
                request.excludeRemoteIds(),
                Boolean.TRUE.equals(request.balanced()),
                user.keycloakId(),
                user.username(),
                ephemeral ? PacketVisibility.EPHEMERAL : PacketVisibility.defaultForNewPackets());
        return ImportResult.from(outcome);
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /** Validates a requested count: null means the default, below {@code min} is rejected, above {@code max} is clamped. */
    static int clampCount(String field, Integer requested, int min, int max) {
        if (requested == null) {
            return Math.min(DEFAULT_COUNT, max);
        }
        if (requested < min) {
            throw new InvalidApiRequestException(field + " must be at least " + min);
        }
        return Math.min(requested, max);
    }

    /** Total bank tossups per category, e.g. {"Science": 45000, ...}. */
    @GetMapping("/category-counts")
    public java.util.Map<String, Object> categoryCounts() {
        return importService.categoryCounts();
    }

    /** Bank tossup counts per category, subcategory, and alternate subcategory. */
    @GetMapping("/taxonomy-counts")
    public java.util.Map<String, Object> taxonomyCounts() {
        return importService.taxonomyCounts();
    }

    /** Live count of bank questions matching a filter (for the Generate UI's breadth preview). */
    @PostMapping("/count")
    public QbreaderImportService.AvailableCount count(@RequestBody RandomRequest request) {
        QbRandomFilter filter = new QbRandomFilter(
                request.categories(), request.subcategories(), request.alternateSubcategories(),
                request.difficulties(), request.minYear(), request.maxYear(), request.standardOnly());
        return importService.countAvailable(filter);
    }

    public record RandomRequest(List<String> categories, List<String> subcategories,
                                List<String> alternateSubcategories,
                                List<Integer> difficulties, Integer minYear, Integer maxYear,
                                Boolean standardOnly, Integer tossupCount, Integer bonusCount,
                                String name, List<String> excludeRemoteIds, Boolean balanced) {}

    /**
     * The created packet's id and name, its answer-free {@code tossupCount}/
     * {@code bonusCount}, plus the qbreader ids of the bank questions used, which ng
     * passes back as {@code excludeRemoteIds} on the next import so a match doesn't
     * repeat questions. Returned for EPHEMERAL packets too (accepted risk, Q-M2-04):
     * they identify questions from the public qbreader set, whose text and answers are
     * already public on qbreader itself, so no Sockbowl-private content is exposed. A
     * caller determined to look the answers up there could, but that caller is the one
     * generating the game (the host), and the ids don't make the packet readable here.
     *
     * <p>{@code tossupCount}/{@code bonusCount} exist so a caller that gets an EPHEMERAL
     * packet back (a guest or player, D15) can show a "N tossups / N bonuses" summary
     * without re-reading the packet through GraphQL, which returns {@code null} for a
     * game-only packet ({@code PacketReadPolicy}) — see ng's WP-FIXN4.
     */
    public record ImportResult(String id, String name, int tossupCount, int bonusCount, List<String> usedRemoteIds) {
        static ImportResult from(ImportOutcome o) {
            return new ImportResult(o.packet().getId(), o.packet().getName(),
                    o.tossupCount(), o.bonusCount(), o.usedRemoteIds());
        }
    }
}
