package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TossupRepository extends Neo4jRepository<Tossup, String> {

    /** Removes the tossup's subcategory (PB-09): deletes its outgoing {@code SUBCATEGORY_IS}. */
    @Query("MATCH (:Tossup {id: $id})-[r:SUBCATEGORY_IS]->(:Subcategory) DELETE r")
    void clearSubcategory(@Param("id") String id);
}
