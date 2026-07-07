package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.repository.BankDimensionsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the real dimensions (difficulty levels + year range) present in the local
 * question bank so the Generate UI can bound its controls to values that exist.
 *
 * <p>Lives under {@code /api/qbreader} for consistency with {@link QbreaderController};
 * the underlying data is the same {@code :BankTossup} bank.
 */
@RestController
@RequestMapping("/api/qbreader")
public class BankDimensionsController {

    private final BankDimensionsRepository dimensionsRepository;

    public BankDimensionsController(BankDimensionsRepository dimensionsRepository) {
        this.dimensionsRepository = dimensionsRepository;
    }

    /**
     * Distinct difficulty levels and the year range/values present in the bank, e.g.
     * {@code {"difficulties":[1,2,3],"minYear":2005,"maxYear":2024,"years":[2005,...]}}.
     * Empty bank yields empty arrays and null bounds — never an error.
     */
    @GetMapping("/dimensions")
    public Map<String, Object> dimensions() {
        List<Integer> difficulties = dimensionsRepository.distinctDifficulties();
        List<Integer> years = dimensionsRepository.distinctYears();

        if (difficulties == null) {
            difficulties = List.of();
        }
        if (years == null) {
            years = List.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("difficulties", difficulties);
        result.put("minYear", years.isEmpty() ? null : years.get(0));
        result.put("maxYear", years.isEmpty() ? null : years.get(years.size() - 1));
        result.put("years", years);
        return result;
    }
}
