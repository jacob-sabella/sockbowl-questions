package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DifficultyRepository extends Neo4jRepository<Difficulty, String> {
    Optional<Difficulty> findByName(String name);
}
