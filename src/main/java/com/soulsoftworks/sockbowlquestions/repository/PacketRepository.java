package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.graphql.data.GraphQlRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@GraphQlRepository
public interface PacketRepository extends Neo4jRepository<Packet, String> {
    Packet getPacketById(String id);

    @Query("MATCH (p:Packet) WHERE toLower(p.name) CONTAINS toLower($name) RETURN p")
    List<Packet> searchByName(String name);
}
