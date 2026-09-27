package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taxonomy creation, moved from {@code PacketAuthoringServiceTest} with
 * {@link TaxonomyService} in M3 Q1 (behavior unchanged; Q4 adds dedupe, rename, merge).
 */
@ExtendWith(MockitoExtension.class)
class TaxonomyServiceTest {

    @Mock private DifficultyRepository difficultyRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private SubcategoryRepository subcategoryRepository;

    private TaxonomyService service;

    @BeforeEach
    void setUp() {
        service = new TaxonomyService(difficultyRepository, categoryRepository, subcategoryRepository);
    }

    /* Moved unchanged from PacketAuthoringServiceTest. */

    @Test
    void createSubcategory_missingCategory_throwsNotFound() {
        when(categoryRepository.findById("c")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createSubcategory("Bio", "c"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createDifficulty_persists() {
        when(difficultyRepository.save(any(Difficulty.class))).thenAnswer(inv -> inv.getArgument(0));
        Difficulty result = service.createDifficulty("Hard");
        assertThat(result.getName()).isEqualTo("Hard");
    }

    /* Pin the rest of the moved behavior so Q4's changes are deliberate. */

    @Test
    void createCategory_trimsAndPersists() {
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));
        Category result = service.createCategory("  Science ");
        assertThat(result.getName()).isEqualTo("Science");
    }

    @Test
    void createCategory_blankName_isRejected() {
        assertThatThrownBy(() -> service.createCategory("  "))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createSubcategory_linksToCategory() {
        Category science = Category.builder().name("Science").build();
        science.setId("c");
        when(categoryRepository.findById("c")).thenReturn(Optional.of(science));
        when(subcategoryRepository.save(any(Subcategory.class))).thenAnswer(inv -> inv.getArgument(0));

        Subcategory result = service.createSubcategory("Biology", "c");

        assertThat(result.getName()).isEqualTo("Biology");
        assertThat(result.getCategory()).isSameAs(science);
    }

    @Test
    void createDifficulty_blankName_isRejected() {
        assertThatThrownBy(() -> service.createDifficulty(null))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(difficultyRepository, never()).save(any());
    }
}
