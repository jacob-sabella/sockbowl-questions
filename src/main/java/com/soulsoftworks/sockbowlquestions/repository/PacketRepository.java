package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PacketRepository extends Neo4jRepository<Packet, String> {

    Packet getPacketById(String id);

    @Query("CALL db.index.fulltext.queryNodes('packetNameIndex', $name + '*') YIELD node RETURN node")
    List<Packet> searchByName(String name);
}
