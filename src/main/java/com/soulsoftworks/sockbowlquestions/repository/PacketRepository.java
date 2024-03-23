package com.soulsoftworks.sockbowlquestions.repository;


import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PacketRepository extends Neo4jRepository<Packet, String> {
    Packet getPacketById(String id);
}