package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taxonomy authoring: difficulties, categories and subcategories (PB-10, D4). Split out
 * of {@link PacketAuthoringService} in M3 Q1 with the create methods unchanged.
 *
 * <p>TODO(Q4): case-insensitive idempotent create (MERGE on {@code nameKey}), the
 * {@code taxonomy.name-max} limit, rename and merge.
 */
@Service
public class TaxonomyService {

    private final DifficultyRepository difficultyRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;

    public TaxonomyService(DifficultyRepository difficultyRepository,
                           CategoryRepository categoryRepository,
                           SubcategoryRepository subcategoryRepository) {
        this.difficultyRepository = difficultyRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
    }

    @Transactional
    public Difficulty createDifficulty(String name) {
        Difficulty difficulty = new Difficulty();
        difficulty.setName(requireText(name, "Difficulty name"));
        return difficultyRepository.save(difficulty);
    }

    @Transactional
    public Category createCategory(String name) {
        Category category = Category.builder().name(requireText(name, "Category name")).build();
        return categoryRepository.save(category);
    }

    @Transactional
    public Subcategory createSubcategory(String name, String categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ResourceNotFoundException.of("Category", categoryId));
        Subcategory subcategory = Subcategory.builder()
                .name(requireText(name, "Subcategory name"))
                .category(category)
                .build();
        return subcategoryRepository.save(subcategory);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidApiRequestException(field + " must not be blank");
        }
        return value.trim();
    }
}
