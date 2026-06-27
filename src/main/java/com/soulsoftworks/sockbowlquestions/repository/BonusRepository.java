package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BonusRepository extends Neo4jRepository<Bonus, String> {
}
