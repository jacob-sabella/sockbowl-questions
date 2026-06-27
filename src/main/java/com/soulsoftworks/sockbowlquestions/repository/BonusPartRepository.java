package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BonusPartRepository extends Neo4jRepository<BonusPart, String> {
}
