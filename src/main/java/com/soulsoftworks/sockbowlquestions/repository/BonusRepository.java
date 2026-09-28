package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BonusRepository extends Neo4jRepository<Bonus, String> {

    /**
     * Removes the bonus's subcategory (PB-09). {@code SUBCATEGORY_IS} points from the
     * subcategory to the bonus ({@code Bonus.subcategory} is mapped INCOMING).
     */
    @Query("MATCH (:Subcategory)-[r:SUBCATEGORY_IS]->(:Bonus {id: $id}) DELETE r")
    void clearSubcategory(@Param("id") String id);
}
