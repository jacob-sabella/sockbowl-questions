package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
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

    private final QbreaderImportService importService;

    public QbreaderController(QbreaderImportService importService) {
        this.importService = importService;
    }

    /**
     * Generate and persist a packet matching the given filters, optionally excluding
     * questions the caller has already seen (their qbreader ids). Returns the qbreader
     * ids actually used so the caller can record them per user.
     */
    @PostMapping("/import-random")
    public ImportResult importRandom(@RequestBody RandomRequest request, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt); // guest() if jwt is null (anonymous game-hosting flow)
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
                request.tossupCount() == null ? 20 : request.tossupCount(),
                request.bonusCount() == null ? 20 : request.bonusCount(),
                request.name(),
                request.excludeRemoteIds(),
                Boolean.TRUE.equals(request.balanced()),
                user.keycloakId(),
                user.username());
        return ImportResult.from(outcome);
    }

    /**
     * @param excludeRemoteIds qbreader ids to avoid (already seen by this user)
     */
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
