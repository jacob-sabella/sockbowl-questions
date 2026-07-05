package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubcategoryRepository extends Neo4jRepository<Subcategory, String> {
    Optional<Subcategory> findByName(String name);

    @Query("MATCH (s:Subcategory)-[:SUBCATEGORY_OF]->(c:Category) " +
           "WHERE s.name = $subcategoryName AND c.name = $categoryName " +
           "RETURN s, c")
    Optional<Subcategory> findByNameAndCategoryName(String subcategoryName, String categoryName);
}
