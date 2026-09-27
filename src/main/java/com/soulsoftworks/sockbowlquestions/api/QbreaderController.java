package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private final QbreaderImportService importService;
    private final int maxTossups;
    private final int maxBonuses;

    public QbreaderController(QbreaderImportService importService,
                              @Value("${sockbowl.import.max-tossups:30}") int maxTossups,
                              @Value("${sockbowl.import.max-bonuses:30}") int maxBonuses) {
        this.importService = importService;
        this.maxTossups = maxTossups;
        this.maxBonuses = maxBonuses;
    }

    /**
     * Generate and persist a packet matching the given filters, optionally excluding
     * questions the caller has already seen (their qbreader ids). Returns the qbreader
     * ids actually used so the caller can record them per user.
     *
     * <p>With auth enabled this requires {@code packet:create} (AUTH-07, D3) and the
     * caller becomes the packet's owner. With auth disabled method security is off,
     * so anyone may import and the packet is ownerless, as before. Either way the
     * counts are bounded: {@code tossupCount} below 1 (or {@code bonusCount} below 0;
     * a tossup-only packet is allowed, as the Generate UI offers) is a 400, an unset
     * value defaults to {@value #DEFAULT_COUNT}, and anything above
     * {@code sockbowl.import.max-tossups} / {@code max-bonuses} (default 30) is clamped
     * down to it.
     */
    @PostMapping("/import-random")
    @PreAuthorize("hasAuthority('packet:create')")
    public ImportResult importRandom(@RequestBody RandomRequest request, @AuthenticationPrincipal Jwt jwt) {
        int tossupCount = clampCount("tossupCount", request.tossupCount(), 1, maxTossups);
        int bonusCount = clampCount("bonusCount", request.bonusCount(), 0, maxBonuses);
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt); // guest() only when auth is disabled
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
                user.username());
        return ImportResult.from(outcome);
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

    public record ImportResult(String id, String name, List<String> usedRemoteIds) {
        static ImportResult from(ImportOutcome o) {
            return new ImportResult(o.packet().getId(), o.packet().getName(), o.usedRemoteIds());
        }
    }
}
