package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;

@Controller
public class GraphQLController {

    private final PacketRepository packetRepository;

    public GraphQLController(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    /**
     * Fetches all packets.
     *
     * @return Iterable of Packet
     */
    @QueryMapping
    @PreAuthorize("hasAuthority('packet:read')")
    public Iterable<Packet> getAllPackets() {
        return packetRepository.findAll();
    }

    /**
     * Fetches a packet by its ID.
     *
     * @param id ID of the packet
     * @return Packet if found, else null
     */
    @QueryMapping
    @PreAuthorize("hasAuthority('packet:read')")
    public Packet getPacketById(@Argument String id) {
        Optional<Packet> packetOpt = packetRepository.findById(id);
        return packetOpt.orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('packet:read')")
    public List<Packet> searchPacketsByName(@Argument String name) {
        return packetRepository.searchByName(name);
    }
}