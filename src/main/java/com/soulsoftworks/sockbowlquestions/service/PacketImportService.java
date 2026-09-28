package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.ImportPacketInput;
import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.dto.ImportPacketResultDto;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.packetio.CategoryTag;
import com.soulsoftworks.sockbowlquestions.packetio.PacketExportFormat;
import com.soulsoftworks.sockbowlquestions.packetio.PacketLimits;
import com.soulsoftworks.sockbowlquestions.packetio.ParseIssue;
import com.soulsoftworks.sockbowlquestions.packetio.ParseResult;
import com.soulsoftworks.sockbowlquestions.packetio.ParsedBonus;
import com.soulsoftworks.sockbowlquestions.packetio.ParsedBonusPart;
import com.soulsoftworks.sockbowlquestions.packetio.ParsedTossup;
import com.soulsoftworks.sockbowlquestions.packetio.PlaintextPacketFormatter;
import com.soulsoftworks.sockbowlquestions.packetio.PlaintextPacketParser;
import com.soulsoftworks.sockbowlquestions.quota.ContentQuotaGuard;
import com.soulsoftworks.sockbowlquestions.repository.PacketImportRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plaintext import, clone and export (D5, plan 3.1.8). The parser and formatter are
 * pure/framework-free ({@code packetio}); this service is the only place that touches
 * the database, resolving category tags against existing taxonomy and writing (or
 * reading back) full packets through {@link PacketImportRepository}.
 */
@Service
public class PacketImportService {

    /** Recorded as {@code createdVia} on every packet {@link #importPacket} creates. */
    public static final String CREATED_VIA_IMPORT = "TEXT_IMPORT";
    /** Recorded as {@code createdVia} on every packet {@link #clonePacket} creates. */
    public static final String CREATED_VIA_CLONE = "CLONED";

    private static final String COPY_SUFFIX = " (copy)";

    private final PlaintextPacketParser parser = new PlaintextPacketParser();
    private final PlaintextPacketFormatter formatter = new PlaintextPacketFormatter();

    private final PacketImportRepository packetImportRepository;
    private final PacketRepository packetRepository;
    private final PacketReadPolicy packetReadPolicy;
    private final PacketLimitsProperties limitsProperties;
    private final PacketValidator validator;
    private final ContentQuotaGuard contentQuotaGuard;
    private final SecurityAuditorAware securityAuditorAware;

    public PacketImportService(PacketImportRepository packetImportRepository,
                               PacketRepository packetRepository,
                               PacketReadPolicy packetReadPolicy,
                               PacketLimitsProperties limitsProperties,
                               PacketValidator validator,
                               ContentQuotaGuard contentQuotaGuard,
                               SecurityAuditorAware securityAuditorAware) {
        this.packetImportRepository = packetImportRepository;
        this.packetRepository = packetRepository;
        this.packetReadPolicy = packetReadPolicy;
        this.limitsProperties = limitsProperties;
        this.validator = validator;
        this.contentQuotaGuard = contentQuotaGuard;
        this.securityAuditorAware = securityAuditorAware;
    }

    /* --------------------------------------- import --------------------------------------- */

    /**
     * Deliberately <b>not</b> {@code @Transactional} (INT1, mirroring Q-V1-01 and
     * {@code PacketAuthoringService#createPacket}): the actual create below runs inside
     * {@link ContentQuotaGuard#withPacketsOwnedSlot}, which releases its per-owner Redis
     * lock in a {@code finally} block. A method-level {@code @Transactional} here would
     * keep the Neo4j write uncommitted until this method returns — after the lock is
     * already released — letting a second concurrent caller acquire the freed lock and
     * count this not-yet-committed packet away, overshooting {@code packets-owned}. The
     * single {@code createImportedPacket} write is transactional on its own (a Spring
     * Data repository method), which is what actually needs to commit before the lock
     * is released, and does.
     */
    public ImportPacketResultDto importPacket(ImportPacketInput input, AuthenticatedUser user) {
        // Q-M3V1-05: an explicit name gets the same name-max rule as createPacket/renamePacket;
        // a suggested one was already shortened (with a WARNING) by the parser.
        String explicitName = input.name() == null || input.name().isBlank()
                ? null : validator.packetName(input.name());
        ParseResult parsed = parser.parse(input.text(), limitsFrom(limitsProperties));
        ParseResult resolved = resolveTaxonomy(parsed);

        if (input.isDryRun()) {
            // INT1 / M4-UQ-01: a dry run writes nothing, so neither packets-owned nor
            // imports is ever touched for it.
            return ImportPacketResultDto.preview(resolved);
        }

        boolean hasErrors = resolved.issues().stream().anyMatch(ParseIssue::isError);
        if (hasErrors && !input.isSkipInvalid()) {
            // Refused, not thrown: the caller gets back the same preview so the UI can
            // show the issues and offer "skip invalid items" rather than a bare error.
            // Nothing is created, so (like a dry run) no quota is touched.
            return ImportPacketResultDto.refused(resolved);
        }
        if (resolved.tossups().isEmpty()) {
            return ImportPacketResultDto.refused(resolved);
        }

        String name = validator.packetName(firstNonBlank(explicitName, resolved.suggestedName(), "Imported packet"));
        List<Map<String, Object>> tossupRows = toTossupRows(resolved.tossups());
        List<Map<String, Object>> bonusRows = toBonusRows(resolved.bonuses());

        // INT1 / M4-UQ-01: a committed import counts toward the caller's packets-owned
        // quota (serialized per owner, Q-V1-01) and today's imports counter (refunded if
        // the write then fails); dryRun and refused returns above never reach here.
        Packet packet = contentQuotaGuard.withPacketsOwnedSlot(user.keycloakId(), () -> {
            ContentQuotaGuard.ImportCharge charge = contentQuotaGuard.chargeImport(user.keycloakId());
            try {
                String id = packetImportRepository.createImportedPacket(
                        name, blankToNull(input.difficultyId()), tossupRows, bonusRows,
                        user.keycloakId(), user.username(), PacketVisibility.DRAFT.name(), CREATED_VIA_IMPORT,
                        ContentSource.TEXT_IMPORT.name(), securityAuditorAware.currentAuditorValue(),
                        Instant.now().toString());
                return loadCreated(id);
            } catch (RuntimeException e) {
                charge.refund();
                throw e;
            }
        });
        return ImportPacketResultDto.committed(packet, resolved);
    }

    /* --------------------------------------- clone ----------------------------------------- */

    /** Not {@code @Transactional}; see {@link #importPacket}'s Javadoc for why. */
    public Packet clonePacket(String id, String name, AuthenticatedUser user) {
        Packet source = requireReadableFull(id);

        List<Map<String, Object>> tossupRows = cloneTossupRows(source);
        List<Map<String, Object>> bonusRows = cloneBonusRows(source);
        String cloneName = name == null || name.isBlank()
                ? defaultCloneName(source.getName())
                : validator.packetName(name);
        String difficultyId = source.getDifficulty() != null ? source.getDifficulty().getId() : null;

        // INT1 / M4-UQ-01: a clone counts toward packets-owned like any other owned
        // create (serialized per owner, Q-V1-01), but never charges the imports quota
        // (it isn't an import).
        return contentQuotaGuard.withPacketsOwnedSlot(user.keycloakId(), () -> {
            String newId = packetImportRepository.createImportedPacket(
                    cloneName, difficultyId, tossupRows, bonusRows,
                    user.keycloakId(), user.username(), PacketVisibility.DRAFT.name(), CREATED_VIA_CLONE,
                    ContentSource.CLONED.name(), securityAuditorAware.currentAuditorValue(),
                    Instant.now().toString());
            return loadCreated(newId);
        });
    }

    /**
     * {@code "<name> (copy)"}, with the source name shortened so the whole thing stays
     * within name-max (Q-M3V1-05): a 200-char name must not become a 207-char clone.
     */
    private String defaultCloneName(String sourceName) {
        String base = sourceName == null || sourceName.isBlank() ? "Untitled packet" : sourceName.trim();
        int nameMax = limitsProperties.getLimits().getNameMax();
        String truncated = PlaintextPacketParser.truncate(base, Math.max(1, nameMax - COPY_SUFFIX.length()));
        return validator.packetName(truncated + COPY_SUFFIX);
    }

    /* --------------------------------------- export ---------------------------------------- */

    public String exportPacket(String id, PacketExportFormat format) {
        Packet source = requireReadableFull(id);
        // PLAINTEXT is the only format M3 ships (PB-06); the argument is still taken so
        // a future format can be added without a breaking schema change.
        return formatter.format(source);
    }

    /* ---------------------------------- shared read gate ------------------------------------ */

    /**
     * Loads {@code id} and applies the clone/export visibility rule (plan 3.1.8): an
     * invisible packet (unknown id, or someone else's non-public packet) throws
     * {@link ResourceNotFoundException} (no existence oracle); a visible packet the
     * caller may not read in full throws {@link AccessDeniedException} (FORBIDDEN).
     */
    private Packet requireReadableFull(String id) {
        Authentication auth = PacketReadPolicy.currentAuthentication();
        Packet packet = packetRepository.findById(id).orElse(null);
        if (!packetReadPolicy.canSee(auth, packet)) {
            throw ResourceNotFoundException.of("Packet", id);
        }
        if (!packetReadPolicy.canReadFull(auth, packet)) {
            throw new AccessDeniedException("Packet " + id + " is not fully readable by this caller");
        }
        return packet;
    }

    private Packet loadCreated(String id) {
        return packetRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Packet " + id + " was not found right after creation"));
    }

    /* ------------------------------------ taxonomy resolution -------------------------------- */

    private ParseResult resolveTaxonomy(ParseResult parsed) {
        List<ParseIssue> issues = new ArrayList<>(parsed.issues());
        List<ParsedTossup> tossups = new ArrayList<>(parsed.tossups().size());
        for (ParsedTossup t : parsed.tossups()) {
            Subcategory resolved = resolveTag(t.categoryTag(), t.line(), issues);
            tossups.add(resolved == null ? t : t.withSubcategory(resolved));
        }
        List<ParsedBonus> bonuses = new ArrayList<>(parsed.bonuses().size());
        for (ParsedBonus b : parsed.bonuses()) {
            Subcategory resolved = resolveTag(b.categoryTag(), b.line(), issues);
            bonuses.add(resolved == null ? b : b.withSubcategory(resolved));
        }
        return new ParseResult(parsed.suggestedName(), tossups, bonuses, issues);
    }

    /** Null when the tag is blank (no tag at all) or doesn't resolve (a warning is then added). */
    private Subcategory resolveTag(String rawTag, int line, List<ParseIssue> issues) {
        CategoryTag.TagParts parts = CategoryTag.split(rawTag);
        if (parts == null) {
            return null;
        }
        Subcategory resolved = packetImportRepository
                .findTaxonomyByKeys(parts.category(), parts.subcategory())
                .orElse(null);
        if (resolved == null) {
            issues.add(ParseIssue.warning(ParseIssue.UNKNOWN_CATEGORY_TAG, line,
                    "Unknown category tag '" + rawTag.trim() + "'"));
        }
        return resolved;
    }

    /* ---------------------------------------- row mapping ------------------------------------ */

    private static List<Map<String, Object>> toTossupRows(List<ParsedTossup> tossups) {
        List<Map<String, Object>> rows = new ArrayList<>(tossups.size());
        int order = 0;
        for (ParsedTossup t : tossups) {
            Map<String, Object> row = new HashMap<>();
            row.put("question", t.question());
            row.put("answer", t.answer());
            row.put("subcategoryId", t.subcategory() != null ? t.subcategory().getId() : null);
            row.put("order", order++);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> toBonusRows(List<ParsedBonus> bonuses) {
        List<Map<String, Object>> rows = new ArrayList<>(bonuses.size());
        int order = 0;
        for (ParsedBonus b : bonuses) {
            Map<String, Object> row = new HashMap<>();
            row.put("preamble", b.preamble());
            row.put("subcategoryId", b.subcategory() != null ? b.subcategory().getId() : null);
            row.put("order", order++);
            List<Map<String, Object>> parts = new ArrayList<>(b.parts().size());
            int partOrder = 0;
            for (ParsedBonusPart part : b.parts()) {
                Map<String, Object> partRow = new HashMap<>();
                partRow.put("question", part.question());
                partRow.put("answer", part.answer());
                partRow.put("order", partOrder++);
                parts.add(partRow);
            }
            row.put("parts", parts);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> cloneTossupRows(Packet source) {
        List<ContainsTossup> rels = source.getTossups() == null ? List.of()
                : source.getTossups().stream()
                        .sorted(Comparator.comparing(r -> orderOf(r.getOrder())))
                        .toList();
        List<Map<String, Object>> rows = new ArrayList<>(rels.size());
        int order = 0;
        for (ContainsTossup rel : rels) {
            Tossup t = rel.getTossup();
            if (t == null) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("question", t.getQuestion());
            row.put("answer", t.getAnswer());
            row.put("subcategoryId", t.getSubcategory() != null ? t.getSubcategory().getId() : null);
            row.put("order", order++);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> cloneBonusRows(Packet source) {
        List<ContainsBonus> rels = source.getBonuses() == null ? List.of()
                : source.getBonuses().stream()
                        .sorted(Comparator.comparing(r -> orderOf(r.getOrder())))
                        .toList();
        List<Map<String, Object>> rows = new ArrayList<>(rels.size());
        int order = 0;
        for (ContainsBonus rel : rels) {
            Bonus b = rel.getBonus();
            if (b == null) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("preamble", b.getPreamble());
            row.put("subcategoryId", b.getSubcategory() != null ? b.getSubcategory().getId() : null);
            row.put("order", order++);

            List<HasBonusPart> partRels = b.getBonusParts() == null ? List.of()
                    : b.getBonusParts().stream()
                            .sorted(Comparator.comparing(r -> orderOf(r.getOrder())))
                            .toList();
            List<Map<String, Object>> parts = new ArrayList<>(partRels.size());
            int partOrder = 0;
            for (HasBonusPart partRel : partRels) {
                BonusPart part = partRel.getBonusPart();
                if (part == null) {
                    continue;
                }
                Map<String, Object> partRow = new HashMap<>();
                partRow.put("question", part.getQuestion());
                partRow.put("answer", part.getAnswer());
                partRow.put("order", partOrder++);
                parts.add(partRow);
            }
            row.put("parts", parts);
            rows.add(row);
        }
        return rows;
    }

    private static int orderOf(Integer order) {
        return order == null ? Integer.MAX_VALUE : order;
    }

    private static PacketLimits limitsFrom(PacketLimitsProperties props) {
        PacketLimitsProperties.Limits l = props.getLimits();
        return new PacketLimits(l.getNameMax(), l.getQuestionMax(), l.getAnswerMax(), l.getPreambleMax(),
                l.getMaxTossups(), l.getMaxBonuses(), l.getMaxPartsPerBonus(), l.getMinPartsPerBonus(),
                props.getImport().getMaxBytes());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return fallback;
    }
}
