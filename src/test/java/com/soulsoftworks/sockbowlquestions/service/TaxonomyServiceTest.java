package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Taxonomy creation (moved from {@code PacketAuthoringServiceTest} in M3 Q1) plus
 * dedupe/rename/merge (M3 Q4, D4, plan 3.1.6). Merge Cypher against a real Neo4j is
 * covered by {@code TaxonomyMergeCypherIT}; this class is Mockito-only and pins the
 * decisions that don't need a database: which repository calls happen, in what order,
 * and which exception each rejection throws.
 */
@ExtendWith(MockitoExtension.class)
class TaxonomyServiceTest {

    @Mock private DifficultyRepository difficultyRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private SubcategoryRepository subcategoryRepository;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Neo4jClient neo4j;

    private TaxonomyService service;

    @BeforeEach
    void setUp() {
        service = new TaxonomyService(difficultyRepository, categoryRepository, subcategoryRepository,
                new PacketLimitsProperties(), neo4j);
    }

    /* ---------------------------- create: idempotent ---------------------------- */

    @Test
    void createCategory_caseInsensitiveCreate_returnsExistingNode() {
        Category existing = Category.builder().id("existing-id").name("Science").build();
        when(categoryRepository.mergeCreateByNameKey("science", "SCIENCE")).thenReturn("existing-id");
        when(categoryRepository.findById("existing-id")).thenReturn(Optional.of(existing));

        Category result = service.createCategory("SCIENCE");

        assertThat(result).isSameAs(existing);
        verify(categoryRepository).mergeCreateByNameKey("science", "SCIENCE");
    }

    @Test
    void createCategory_trimsAndPersists() {
        when(categoryRepository.mergeCreateByNameKey("science", "Science")).thenReturn("new-id");
        when(categoryRepository.findById("new-id"))
                .thenReturn(Optional.of(Category.builder().id("new-id").name("Science").build()));

        Category result = service.createCategory("  Science ");

        assertThat(result.getName()).isEqualTo("Science");
        verify(categoryRepository).mergeCreateByNameKey("science", "Science");
    }

    @Test
    void createCategory_blankName_isRejected() {
        assertThatThrownBy(() -> service.createCategory("  "))
                .isInstanceOf(InvalidApiRequestException.class);
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void createCategory_tooLongName_isRejected() {
        PacketLimitsProperties limits = new PacketLimitsProperties();
        limits.getTaxonomy().setNameMax(5);
        service = new TaxonomyService(difficultyRepository, categoryRepository, subcategoryRepository, limits, neo4j);

        assertThatThrownBy(() -> service.createCategory("Astronomy"))
                .isInstanceOf(ValidationFailedException.class);
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void createDifficulty_persists() {
        when(difficultyRepository.mergeCreateByNameKey("hard", "Hard")).thenReturn("d1");
        when(difficultyRepository.findById("d1")).thenReturn(Optional.of(Difficulty.builder().id("d1").name("Hard").build()));

        Difficulty result = service.createDifficulty("Hard");

        assertThat(result.getName()).isEqualTo("Hard");
    }

    @Test
    void createDifficulty_blankName_isRejected() {
        assertThatThrownBy(() -> service.createDifficulty(null))
                .isInstanceOf(InvalidApiRequestException.class);
        verifyNoInteractions(difficultyRepository);
    }

    @Test
    void createSubcategory_missingCategory_throwsNotFound() {
        when(subcategoryRepository.mergeCreateByNameKey("c", "bio", "Bio")).thenReturn(null);

        assertThatThrownBy(() -> service.createSubcategory("Bio", "c"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSubcategory_linksToCategory() {
        Category science = Category.builder().id("c").name("Science").build();
        Subcategory withCategory = Subcategory.builder().id("s1").name("Biology").category(science).build();
        when(subcategoryRepository.mergeCreateByNameKey("c", "biology", "Biology")).thenReturn("s1");
        when(subcategoryRepository.findById("s1")).thenReturn(Optional.of(withCategory));

        Subcategory result = service.createSubcategory("Biology", "c");

        assertThat(result.getName()).isEqualTo("Biology");
        assertThat(result.getCategory()).isSameAs(science);
    }

    /* -------------------------------- rename -------------------------------- */

    @Test
    void renameCategory_collision_isRejected() {
        when(categoryRepository.findById("c1")).thenReturn(Optional.of(Category.builder().id("c1").name("Old").build()));
        when(categoryRepository.findByNameKey("science")).thenReturn(Optional.of(Category.builder().id("c2").name("Science").build()));

        assertThatThrownBy(() -> service.renameCategory("c1", "Science"))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("merge");
        verify(categoryRepository, never()).renameById(anyString(), anyString(), anyString());
    }

    @Test
    void renameCategory_sameNodeKeyIsNotACollision() {
        when(categoryRepository.findByNameKey("science!")).thenReturn(Optional.of(Category.builder().id("c1").name("Science").build()));
        when(categoryRepository.findById("c1"))
                .thenReturn(Optional.of(Category.builder().id("c1").name("Science").build()),
                        Optional.of(Category.builder().id("c1").name("Science!").build()));

        service.renameCategory("c1", "Science!");

        verify(categoryRepository).renameById("c1", "Science!", "science!");
    }

    @Test
    void renameCategory_missing_throwsNotFound() {
        when(categoryRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameCategory("missing", "New"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void renameSubcategory_collisionScopedToCategory_isRejected() {
        Category category = Category.builder().id("cat1").name("Science").build();
        when(subcategoryRepository.findById("s1"))
                .thenReturn(Optional.of(Subcategory.builder().id("s1").name("Bio").category(category).build()));
        when(subcategoryRepository.findIdByNameKeyAndCategoryId("biology", "cat1")).thenReturn(Optional.of("s2"));

        assertThatThrownBy(() -> service.renameSubcategory("s1", "Biology"))
                .isInstanceOf(ValidationFailedException.class);
        verify(subcategoryRepository, never()).renameById(anyString(), anyString(), anyString());
    }

    /* --------------------------------- merge --------------------------------- */

    @Test
    void mergeCategories_intoItself_isRejected() {
        assertThatThrownBy(() -> service.mergeCategories("c1", "c1"))
                .isInstanceOf(ValidationFailedException.class);
        verifyNoInteractions(categoryRepository, subcategoryRepository);
    }

    @Test
    void mergeCategories_missingSource_throwsNotFound() {
        when(categoryRepository.findById("c1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.mergeCategories("c1", "c2"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void mergeCategories_repointsNonCollidingSubcategoriesAndMergesCollidingOnes() {
        when(categoryRepository.findById("source")).thenReturn(Optional.of(Category.builder().id("source").build()));
        when(categoryRepository.findById("target")).thenReturn(Optional.of(Category.builder().id("target").build()));
        when(neo4j.query(anyString()).bind(any()).to(anyString()).fetch().all()).thenReturn(List.of(
                Map.of("id", "sub-unique", "nameKey", "physics"),
                Map.of("id", "sub-dupe", "nameKey", "biology")
        ));
        when(subcategoryRepository.findIdByNameKeyAndCategoryId("physics", "target")).thenReturn(Optional.empty());
        when(subcategoryRepository.findIdByNameKeyAndCategoryId("biology", "target")).thenReturn(Optional.of("existing-bio"));

        service.mergeCategories("source", "target");

        verify(subcategoryRepository).repointToCategory("sub-unique", "target");
        verify(subcategoryRepository).mergeInto("sub-dupe", "existing-bio");
        verify(categoryRepository).deleteByIdCascade("source");
    }

    @Test
    void mergeSubcategories_intoItself_isRejected() {
        assertThatThrownBy(() -> service.mergeSubcategories("s1", "s1"))
                .isInstanceOf(ValidationFailedException.class);
        verifyNoInteractions(subcategoryRepository);
    }

    @Test
    void mergeSubcategories_acrossDifferentCategories_isRejected() {
        Category cat1 = Category.builder().id("cat1").build();
        Category cat2 = Category.builder().id("cat2").build();
        when(subcategoryRepository.findById("s1")).thenReturn(Optional.of(Subcategory.builder().id("s1").category(cat1).build()));
        when(subcategoryRepository.findById("s2")).thenReturn(Optional.of(Subcategory.builder().id("s2").category(cat2).build()));

        assertThatThrownBy(() -> service.mergeSubcategories("s1", "s2"))
                .isInstanceOf(ValidationFailedException.class);
        verify(subcategoryRepository, never()).mergeInto(anyString(), anyString());
    }

    @Test
    void mergeSubcategories_sameCategory_merges() {
        Category cat = Category.builder().id("cat1").build();
        when(subcategoryRepository.findById("s1")).thenReturn(Optional.of(Subcategory.builder().id("s1").category(cat).build()));
        when(subcategoryRepository.findById("s2"))
                .thenReturn(Optional.of(Subcategory.builder().id("s2").category(cat).build()),
                        Optional.of(Subcategory.builder().id("s2").category(cat).name("Target").build()));

        Subcategory result = service.mergeSubcategories("s1", "s2");

        verify(subcategoryRepository).mergeInto("s1", "s2");
        assertThat(result.getId()).isEqualTo("s2");
    }

    @Test
    void mergeDifficulties_intoItself_isRejected() {
        assertThatThrownBy(() -> service.mergeDifficulties("d1", "d1"))
                .isInstanceOf(ValidationFailedException.class);
        verifyNoInteractions(difficultyRepository);
    }

    @Test
    void mergeDifficulties_repointsAndDeletesSource() {
        when(difficultyRepository.findById("d1")).thenReturn(Optional.of(Difficulty.builder().id("d1").build()));
        when(difficultyRepository.findById("d2"))
                .thenReturn(Optional.of(Difficulty.builder().id("d2").build()),
                        Optional.of(Difficulty.builder().id("d2").name("Hard").build()));

        Difficulty result = service.mergeDifficulties("d1", "d2");

        verify(difficultyRepository).mergeInto("d1", "d2");
        assertThat(result.getName()).isEqualTo("Hard");
    }
}
