package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.service.TaxonomyService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL taxonomy mutations (PB-10, D4). Every operation requires
 * {@code taxonomy:manage} (moderator and admin after the D4 move). Moved out of
 * {@link PacketAuthoringController} in M3 Q1 with the same rules.
 *
 * <p>TODO(Q4): {@code renameCategory/renameSubcategory/renameDifficulty} and
 * {@code mergeCategories/mergeSubcategories/mergeDifficulties}.
 */
@Controller
public class TaxonomyController {

    private final TaxonomyService taxonomyService;

    public TaxonomyController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('taxonomy:manage')")
    public Difficulty createDifficulty(@Argument String name) {
        return taxonomyService.createDifficulty(name);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('taxonomy:manage')")
    public Category createCategory(@Argument String name) {
        return taxonomyService.createCategory(name);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('taxonomy:manage')")
    public Subcategory createSubcategory(@Argument String name, @Argument String categoryId) {
        return taxonomyService.createSubcategory(name, categoryId);
    }
}
