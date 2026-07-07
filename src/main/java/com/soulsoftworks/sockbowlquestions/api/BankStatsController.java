package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.repository.BankStatsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST facade exposing aggregate stats over the local Neo4j question bank for the
 * Generate UI's overview panel. One GET returns totals, the tossup year range, and a
 * per-difficulty histogram.
 *
 * <p>Shares the {@code /api/qbreader} path with {@link QbreaderController} but registers
 * a distinct sub-path, so the two controllers coexist without collision.
 */
@RestController
@RequestMapping("/api/qbreader")
public class BankStatsController {

    private final BankStatsRepository statsRepository;

    public BankStatsController(BankStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    /**
     * Aggregate bank stats, e.g.
     * {@code {tossups, bonuses, sets, minYear, maxYear, difficulties: {"1": 1234, ...}}}.
     * Empty-bank safe: year fields fall back to 0 and the difficulty map to empty.
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tossups", statsRepository.countTossups());
        result.put("bonuses", statsRepository.countBonuses());
        result.put("sets", statsRepository.countSets());

        Map<String, Object> years = firstRow(statsRepository.yearRange());
        result.put("minYear", years.get("minYear") == null ? 0 : years.get("minYear"));
        result.put("maxYear", years.get("maxYear") == null ? 0 : years.get("maxYear"));

        result.put("difficulties", firstRow(statsRepository.difficultyCounts()));
        return result;
    }

    /** The single result row (SDN already unwraps the {@code row} column), or an empty map. */
    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() || rows.get(0) == null ? Map.of() : rows.get(0);
    }
}
