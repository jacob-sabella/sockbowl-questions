package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.quota.ContentQuotaGuard;
import com.soulsoftworks.sockbowlquestions.repository.BankRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds native sockbowl {@link Packet}s from the LOCAL Neo4j question bank
 * ({@code :BankTossup} / {@code :BankBonus} nodes loaded from the qbreader dump).
 * There is no remote qbreader call — {@link BankRepository} samples the bank with
 * strict, complete filters, and the sampled questions are COPIED into fresh,
 * packet-owned nodes via {@link PacketRepository#batchCreatePacket} (the same proven
 * write path used for authored packets), so a generated packet behaves exactly like
 * an authored one for the game, editing, and deletion.
 */
@Service
public class QbreaderImportService {

    private static final Logger log = LoggerFactory.getLogger(QbreaderImportService.class);

    /** Recorded as {@code createdVia} on every packet this service creates (D15). */
    public static final String CREATED_VIA = "import-random";

    private final BankRepository bankRepository;
    private final PacketRepository packetRepository;
    private final SecurityAuditorAware securityAuditorAware;
    private final ContentQuotaGuard contentQuotaGuard;
    private final PacketReadPolicy packetReadPolicy;

    public QbreaderImportService(BankRepository bankRepository, PacketRepository packetRepository,
                                 SecurityAuditorAware securityAuditorAware, ContentQuotaGuard contentQuotaGuard,
                                 PacketReadPolicy packetReadPolicy) {
        this.bankRepository = bankRepository;
        this.packetRepository = packetRepository;
        this.securityAuditorAware = securityAuditorAware;
        this.contentQuotaGuard = contentQuotaGuard;
        this.packetReadPolicy = packetReadPolicy;
    }

    /**
     * Build a fresh packet from bank questions matching the given filters, excluding
     * any question whose qbreader id is in {@code excludeRemoteIds}.
     *
     * @return the created packet plus the qbreader ids of the questions actually used,
     *         so the caller can record them against a user for future de-duplication.
     */
    /** The 12 canonical qbreader categories (used to spread a balanced mix). */
    private static final List<String> ALL_CATEGORIES = List.of(
            "Literature", "History", "Science", "Fine Arts", "Religion", "Mythology",
            "Philosophy", "Social Science", "Geography", "Current Events", "Other Academic", "Pop Culture");

    public ImportOutcome importRandomPacket(QbRandomFilter filter, int tossupCount, int bonusCount,
                                            String name, Collection<String> excludeRemoteIds, boolean balanced,
                                            String ownerId, String ownerDisplayName) {
        return importRandomPacket(filter, tossupCount, bonusCount, name, excludeRemoteIds, balanced,
                ownerId, ownerDisplayName, PacketVisibility.defaultForNewPackets());
    }

    /**
     * As above, persisting the packet with an explicit visibility. The short form
     * uses the D2 default ({@link PacketVisibility#DRAFT}). A game-only visibility
     * (EPHEMERAL, D15) also stamps {@code ephemeralCreatedAt}, which the TTL cleanup
     * job ({@code EphemeralPacketCleanupJob}) reads; callers pass no owner for it.
     */
    public ImportOutcome importRandomPacket(QbRandomFilter filter, int tossupCount, int bonusCount,
                                            String name, Collection<String> excludeRemoteIds, boolean balanced,
                                            String ownerId, String ownerDisplayName,
                                            PacketVisibility visibility) {
        PacketVisibility effectiveVisibility = visibility == null ? PacketVisibility.defaultForNewPackets() : visibility;
        // M4-UQ-01: an owned import counts toward packets-owned and today's imports
        // (refunded if it fails). A game-only EPHEMERAL import has no owner (D15) and
        // is bounded by the import/import-ip rate policies instead.
        ContentQuotaGuard.ImportCharge charge = ContentQuotaGuard.ImportCharge.NONE;
        if (ownerId != null && !effectiveVisibility.isGameOnly()) {
            contentQuotaGuard.checkPacketsOwned(ownerId);
            charge = contentQuotaGuard.chargeImport(ownerId);
        }
        try {
            return createFromBank(filter, tossupCount, bonusCount, name, excludeRemoteIds, balanced,
                    ownerId, ownerDisplayName, effectiveVisibility);
        } catch (RuntimeException e) {
            charge.refund();
            throw e;
        }
    }

    private ImportOutcome createFromBank(QbRandomFilter filter, int tossupCount, int bonusCount,
                                         String name, Collection<String> excludeRemoteIds, boolean balanced,
                                         String ownerId, String ownerDisplayName,
                                         PacketVisibility effectiveVisibility) {
        List<String> exclude = excludeRemoteIds == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(excludeRemoteIds));

        List<Map<String, Object>> tossups = sampleTossups(filter, exclude, Math.max(1, tossupCount), balanced);
        List<Map<String, Object>> bonuses = sampleBonuses(filter, exclude, Math.max(0, bonusCount), balanced);

        if (tossups.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No bank questions matched the requested filters");
        }

        List<String> usedRemoteIds = new ArrayList<>();
        List<Map<String, Object>> tossupRows = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> t : tossups) {
            String remoteId = clean(str(t.get("remoteId")));
            tossupRows.add(Map.of(
                    "question", clean(str(t.get("question"))),
                    "answer", clean(str(t.get("answer"))),
                    "category", str(t.get("category")),
                    "subcategory", str(t.get("subcategory")),
                    "remoteId", remoteId,
                    "order", order++));
            if (!remoteId.isBlank()) usedRemoteIds.add(remoteId);
        }

        List<Map<String, Object>> bonusRows = new ArrayList<>();
        order = 0;
        for (Map<String, Object> b : bonuses) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> partsIn = (List<Map<String, Object>>) b.get("parts");
            if (partsIn == null || partsIn.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> parts = new ArrayList<>();
            int i = 0;
            for (Map<String, Object> p : partsIn) {
                parts.add(Map.of(
                        "question", clean(str(p.get("question"))),
                        "answer", clean(str(p.get("answer"))),
                        "order", i++));
            }
            String remoteId = clean(str(b.get("remoteId")));
            bonusRows.add(Map.of(
                    "preamble", clean(str(b.get("preamble"))),
                    "category", str(b.get("category")),
                    "subcategory", str(b.get("subcategory")),
                    "remoteId", remoteId,
                    "order", order++,
                    "parts", parts));
            if (!remoteId.isBlank()) usedRemoteIds.add(remoteId);
        }

        Integer diff = (filter == null || filter.difficulties() == null || filter.difficulties().isEmpty())
                ? null : filter.difficulties().get(0);
        String baseName = (name == null || name.isBlank()) ? "Custom packet" : name.trim();
        // A game-only packet is never listed, so its name needn't be unique (and guest
        // imports must not push listed packets' names to "(2)", "(3)", ...).
        String packetName = effectiveVisibility.isGameOnly() ? baseName : uniqueName(baseName, ownerId);

        String id = packetRepository.batchCreatePacket(
                packetName, difficultyLabel(diff), tossupRows, bonusRows, ownerId, ownerDisplayName,
                effectiveVisibility.name(), CREATED_VIA,
                securityAuditorAware.currentAuditorValue(), Instant.now().toString(),
                ContentSource.QBREADER_IMPORT.name());
        log.info("Generated local packet '{}' (id={}, visibility={}, {} tossups, {} bonuses)",
                packetName, id, effectiveVisibility, tossupRows.size(), bonusRows.size());
        return new ImportOutcome(
                Packet.builder().id(id).name(packetName).ownerId(ownerId).ownerDisplayName(ownerDisplayName)
                        .visibility(effectiveVisibility).build(),
                new ArrayList<>(new LinkedHashSet<>(usedRemoteIds)),
                tossupRows.size(), bonusRows.size());
    }

    /**
     * A completed generation: the packet, the qbreader ids of the questions it used, and
     * the answer-free {@code tossupCount}/{@code bonusCount} actually persisted (the
     * bonus count can be lower than requested when a sampled bonus had no parts). ng's
     * Generate flow (FIXN4) reads these off {@code QbreaderController.ImportResult}
     * instead of re-reading the packet from GraphQL, which returns {@code null} for an
     * EPHEMERAL packet.
     */
    public record ImportOutcome(Packet packet, List<String> usedRemoteIds, int tossupCount, int bonusCount) {}

    /** How many bank tossups/bonuses match a filter — a live "breadth" preview for the UI. */
    public AvailableCount countAvailable(QbRandomFilter filter) {
        List<String> cats = emptyToNull(filter == null ? null : filter.categories());
        List<String> subs = emptyToNull(filter == null ? null : filter.subcategories());
        List<String> alts = emptyToNull(filter == null ? null : filter.alternateSubcategories());
        List<Integer> diffs = emptyToNull(filter == null ? null : filter.difficulties());
        Integer minY = filter == null ? null : filter.minYear();
        Integer maxY = filter == null ? null : filter.maxYear();
        boolean std = filter != null && Boolean.TRUE.equals(filter.standardOnly());
        long tossups = bankRepository.countBankTossups(cats, subs, alts, diffs, minY, maxY, std);
        long bonuses = bankRepository.countBankBonuses(cats, subs, alts, diffs, minY, maxY, std);
        return new AvailableCount(tossups, bonuses);
    }

    public record AvailableCount(long tossups, long bonuses) {}

    /** Total bank tossups per category (for the Generate UI's category chips). */
    public Map<String, Object> categoryCounts() {
        return firstOrEmpty(bankRepository.categoryTossupCounts());
    }

    /** Bank tossup counts per category, subcategory, and alternate subcategory. */
    public Map<String, Object> taxonomyCounts() {
        return Map.of(
                "categories", firstOrEmpty(bankRepository.categoryTossupCounts()),
                "subcategories", firstOrEmpty(bankRepository.subcategoryTossupCounts()),
                "alternates", firstOrEmpty(bankRepository.alternateTossupCounts()));
    }

    private static Map<String, Object> firstOrEmpty(List<Map<String, Object>> rows) {
        return rows.isEmpty() || rows.get(0) == null ? Map.of() : rows.get(0);
    }

    /* --------------------------- bank sampling --------------------------- */

    private List<Map<String, Object>> sampleTossups(QbRandomFilter f, List<String> exclude, int count, boolean balanced) {
        List<String> cats = emptyToNull(f == null ? null : f.categories());
        List<String> subs = emptyToNull(f == null ? null : f.subcategories());
        List<String> alts = emptyToNull(f == null ? null : f.alternateSubcategories());
        List<Integer> diffs = emptyToNull(f == null ? null : f.difficulties());
        Integer minY = f == null ? null : f.minYear();
        Integer maxY = f == null ? null : f.maxYear();
        boolean std = f != null && Boolean.TRUE.equals(f.standardOnly());
        if (!balanced) {
            return bankRepository.sampleBankTossups(cats, subs, alts, diffs, minY, maxY, std, exclude, count);
        }
        List<String> strata = cats == null ? ALL_CATEGORIES : cats;
        int per = (int) Math.ceil((double) count / strata.size()) + 1;
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> pooled = new ArrayList<>();
        for (String cat : strata) {
            for (Map<String, Object> m : bankRepository.sampleBankTossups(
                    List.of(cat), subs, alts, diffs, minY, maxY, std, exclude, per)) {
                if (seen.add(String.valueOf(m.get("remoteId")))) pooled.add(m);
            }
        }
        Collections.shuffle(pooled);
        return pooled.size() > count ? new ArrayList<>(pooled.subList(0, count)) : pooled;
    }

    private List<Map<String, Object>> sampleBonuses(QbRandomFilter f, List<String> exclude, int count, boolean balanced) {
        if (count <= 0) {
            return List.of();
        }
        List<String> cats = emptyToNull(f == null ? null : f.categories());
        List<String> subs = emptyToNull(f == null ? null : f.subcategories());
        List<String> alts = emptyToNull(f == null ? null : f.alternateSubcategories());
        List<Integer> diffs = emptyToNull(f == null ? null : f.difficulties());
        Integer minY = f == null ? null : f.minYear();
        Integer maxY = f == null ? null : f.maxYear();
        boolean std = f != null && Boolean.TRUE.equals(f.standardOnly());
        if (!balanced) {
            return bankRepository.sampleBankBonuses(cats, subs, alts, diffs, minY, maxY, std, exclude, count);
        }
        List<String> strata = cats == null ? ALL_CATEGORIES : cats;
        int per = (int) Math.ceil((double) count / strata.size()) + 1;
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> pooled = new ArrayList<>();
        for (String cat : strata) {
            for (Map<String, Object> m : bankRepository.sampleBankBonuses(
                    List.of(cat), subs, alts, diffs, minY, maxY, std, exclude, per)) {
                if (seen.add(String.valueOf(m.get("remoteId")))) pooled.add(m);
            }
        }
        Collections.shuffle(pooled);
        return pooled.size() > count ? new ArrayList<>(pooled.subList(0, count)) : pooled;
    }

    /* -------------------------------------------------------------------- */

    /**
     * A packet with this exact name that {@code ownerId} may see: publicly readable
     * (PUBLISHED, or legacy) packets, plus {@code ownerId}'s own packets of any
     * visibility. Never matches another caller's DRAFT (Q3-01): otherwise the "(2)"
     * suffix on the returned name would reveal that a private packet with that name
     * exists, to a caller who cannot see it through any read query.
     *
     * <p>With {@code sockbowl.auth.enabled=false} (Q4-01) every import is an ownerless
     * DRAFT, so the owner-scoped search above would never match anything (there is no
     * owner to match, and DRAFT is not publicly readable) and de-duplication would
     * silently stop working. Auth off has no private-packet existence oracle to protect
     * (there are no other callers to leak to), so it falls back to the pre-Q3-01,
     * owner-blind {@link PacketRepository#searchListedByName}: every listed (non
     * game-only) packet, regardless of owner.
     */
    private Packet findByExactName(String name, String ownerId) {
        List<Packet> candidates = packetReadPolicy.isAuthEnabled()
                ? packetRepository.searchVisibleByName(name, packetReadPolicy.publiclyReadableVisibilities(),
                        packetReadPolicy.legacyVisibility(), ownerId)
                : packetRepository.searchListedByName(name, packetReadPolicy.unlistedVisibilities(),
                        packetReadPolicy.legacyVisibility());
        return candidates.stream()
                .filter(p -> name.equalsIgnoreCase(p.getName()))
                .findFirst().orElse(null);
    }

    /** Appends a numeric suffix if a packet {@code ownerId} can see with this name already exists. */
    private String uniqueName(String base, String ownerId) {
        if (findByExactName(base, ownerId) == null) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (findByExactName(candidate, ownerId) == null) {
                return candidate;
            }
        }
        return base + " (" + System.identityHashCode(base) + ")";
    }

    /** Maps qbreader's 1-10 difficulty scale to a human-readable sockbowl difficulty. */
    private static String difficultyLabel(Integer d) {
        if (d == null) return "Regular High School";
        if (d <= 2) return "Middle School";
        if (d <= 4) return "Easy High School";
        if (d == 5) return "Regular High School";
        if (d == 6) return "Hard High School";
        if (d <= 8) return "College";
        return "Open";
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
