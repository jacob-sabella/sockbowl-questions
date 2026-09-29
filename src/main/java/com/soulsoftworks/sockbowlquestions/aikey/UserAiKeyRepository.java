package com.soulsoftworks.sockbowlquestions.aikey;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAiKeyRepository extends Neo4jRepository<UserAiKey, String> {
}
