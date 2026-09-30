package com.soulsoftworks.sockbowlquestions.migration;

import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gives the standard difficulties a starter description, so AI generation has
 * something to aim at before anyone edits them in the taxonomy admin. Only a
 * difficulty whose {@code description} was never set is touched: an edited one, or
 * one cleared on purpose (stored as an empty string), is left alone, so this is safe
 * to run on every start and a second run is a no-op. Difficulties are matched by
 * {@code nameKey}; ones not listed here get no description.
 *
 * <p>Disable with {@code sockbowl.migrations.difficulty-descriptions.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sockbowl.migrations.difficulty-descriptions.enabled", havingValue = "true",
        matchIfMissing = true)
public class DifficultyDescriptionSeeder implements ApplicationRunner {

    static final Map<String, String> STARTER_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        STARTER_DESCRIPTIONS.put("elementary school",
                "Players are 8 to 11 years old. Answers are things a curious elementary student meets in class, "
                        + "picture books or everyday life (famous animals, planets, U.S. presidents, well-known "
                        + "stories). Use short sentences and simple words; every clue, even the first, should be "
                        + "gettable by a strong student, and the giveaway should be very easy.");
        STARTER_DESCRIPTIONS.put("middle school",
                "Players are 11 to 14. Answers come from the middle school curriculum and general knowledge: "
                        + "major historical figures and events, core science terms, widely read books, famous "
                        + "artworks and places. Opening clues can be a little harder, but avoid specialist "
                        + "material; the giveaway should be something most middle schoolers have heard of.");
        STARTER_DESCRIPTIONS.put("easy high school",
                "Novice high school teams. Answers are canonical, textbook-level subjects (well-known authors, "
                        + "wars, scientific laws, capitals). Leadins may use a moderately detailed fact, but no "
                        + "clue should need knowledge beyond a good high school course; giveaways are very easy.");
        STARTER_DESCRIPTIONS.put("regular high school",
                "Typical high school competition (NAQT IS-level). Answers are well-known to experienced high "
                        + "school players; leadins use real but less familiar facts, middle clues come from AP-level "
                        + "coursework, and the giveaway is something any solid high school player would get.");
        STARTER_DESCRIPTIONS.put("high school",
                "Typical high school competition (NAQT IS-level). Answers are well-known to experienced high "
                        + "school players; leadins use real but less familiar facts, middle clues come from AP-level "
                        + "coursework, and the giveaway is something any solid high school player would get.");
        STARTER_DESCRIPTIONS.put("hard high school",
                "Top high school teams (national-championship level). Answers can go past the canon into "
                        + "second-tier works, figures and concepts; leadins reward serious study, and the giveaway "
                        + "should still be clear to a strong high school player.");
        STARTER_DESCRIPTIONS.put("college",
                "Regular college quizbowl (ACF Regular / NAQT SCT). Answers span the undergraduate canon and "
                        + "beyond; leadins reward specialist knowledge, and giveaways are what a well-read college "
                        + "player knows.");
        STARTER_DESCRIPTIONS.put("collegiate",
                "Regular college quizbowl (ACF Regular / NAQT SCT). Answers span the undergraduate canon and "
                        + "beyond; leadins reward specialist knowledge, and giveaways are what a well-read college "
                        + "player knows.");
        STARTER_DESCRIPTIONS.put("open",
                "Open / expert level for veteran players. Answers may be obscure or specialist, leadins should "
                        + "stump everyone but experts, and giveaways can assume broad, deep knowledge.");
    }

    private final DifficultyRepository difficultyRepository;

    public DifficultyDescriptionSeeder(DifficultyRepository difficultyRepository) {
        this.difficultyRepository = difficultyRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (RuntimeException e) {
            // Never block startup: generation works without descriptions, and the
            // next start retries.
            log.warn("Difficulty description seeding failed", e);
        }
    }

    /** Seeds every listed difficulty that never had a description; returns how many it set. */
    public long seed() {
        long seeded = 0;
        for (Map.Entry<String, String> entry : STARTER_DESCRIPTIONS.entrySet()) {
            seeded += difficultyRepository.seedDescription(entry.getKey(), entry.getValue());
        }
        log.info("Difficulty descriptions: seeded {} starter description(s)", seeded);
        return seeded;
    }
}
