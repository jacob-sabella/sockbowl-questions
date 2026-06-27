package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TossupRepository extends Neo4jRepository<Tossup, String> {
}
