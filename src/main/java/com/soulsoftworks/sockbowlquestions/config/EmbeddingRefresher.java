package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingRefresher implements CommandLineRunner {
    @Autowired
    private EmbeddingModel embeddingModel;      // Spring AI EmbeddingModel (Ollama)
    @Autowired
    private Neo4jClient neo4jClient;            // Spring Data Neo4j client for DB access

    @Override
    public void run(String... args) {
        // Example: Generate embeddings for all Tossup nodes
        String tossupQuery =
                "MATCH (t:Tossup)-[:CATEGORY_IS]->(cat:Category), " +
                        "      (t)-[:SUBCATEGORY_IS]->(sub:Subcategory), " +
                        "      (t)-[:HAS_DIFFICULTY]->(d:Difficulty) " +
                        "OPTIONAL MATCH (t)<-[:CONTAINS_TOSSUP]-(p:Packet) " +
                        "RETURN id(t) AS id, t.questionText AS question, t.answer AS answer, " +
                        "       cat.name AS category, sub.name AS subcategory, " +
                        "       d.level AS difficulty, p.name AS packet";
        neo4jClient.query(tossupQuery).fetch().all().forEach(row -> {
            // Combine content and structural context into an input string
            String input = String.format(
                    "Tossup: %s Answer: %s. Category: %s, Subcategory: %s, Difficulty: %s%s.",
                    row.get("question"), row.get("answer"),
                    row.get("category"), row.get("subcategory"), row.get("difficulty"),
                    row.get("packet") != null ? (", Packet: " + row.get("packet")) : ""
            );

            // Generate embedding vector using the Ollama model
            float[] vector = embeddingModel.embed(input);
            // Write the embedding back to Neo4j as a node property
            neo4jClient.query("MATCH (t:Tossup) WHERE id(t) = $id SET t.embedding = $vec")
                    .bind(row.get("id")).to("id")
                    .bind(vector).to("vec")
                    .run();
        });
        // Repeat similar steps for other node labels (Category, Subcategory, Difficulty, Packet, Bonus, BonusPart, Event).
        // For each node type, build an appropriate input string and store the resulting vector.
    }
}
