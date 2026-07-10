package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.dto.PacketOwnerDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;

@Controller
public class GraphQLController {

    private final PacketRepository packetRepository;
    private final DifficultyRepository difficultyRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;

    public GraphQLController(PacketRepository packetRepository,
                              DifficultyRepository difficultyRepository,
                              CategoryRepository categoryRepository,
                              SubcategoryRepository subcategoryRepository) {
        this.packetRepository = packetRepository;
        this.difficultyRepository = difficultyRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
    }

    /**
     * Fetches all packets.
     *
     * <p>Intentionally has no {@code @PreAuthorize}: guests (unauthenticated callers,
     * incl. anonymous game-hosting flows) must always be able to read packets. The
     * {@code packet:read} authority remains defined in the RBAC model for the
     * {@code sockbowl-game-backend} service client, but nothing gates on it here.
     *
     * @return Iterable of Packet
     */
    @QueryMapping
    public Iterable<Packet> getAllPackets() {
        return packetRepository.findAll();
    }

    /**
     * Fetches a packet by its ID. No {@code @PreAuthorize} — see {@link #getAllPackets()}.
     *
     * @param id ID of the packet
     * @return Packet if found, else null
     */
    @QueryMapping
    public Packet getPacketById(@Argument String id) {
        Optional<Packet> packetOpt = packetRepository.findById(id);
        return packetOpt.orElse(null);
    }

    /** No {@code @PreAuthorize} — see {@link #getAllPackets()}. */
    @QueryMapping
    public List<Packet> searchPacketsByName(@Argument String name) {
        return packetRepository.searchByName(name);
    }

    /**
     * Fetches all difficulties. No {@code @PreAuthorize} — see {@link #getAllPackets()}.
     *
     * @return Iterable of Difficulty
     */
    @QueryMapping
    public Iterable<Difficulty> getAllDifficulties() {
        return difficultyRepository.findAll();
    }

    /**
     * Fetches all categories. No {@code @PreAuthorize} — see {@link #getAllPackets()}.
     *
     * @return Iterable of Category
     */
    @QueryMapping
    public Iterable<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    /**
     * Fetches all subcategories. No {@code @PreAuthorize} — see {@link #getAllPackets()}.
     *
     * @return Iterable of Subcategory
     */
    @QueryMapping
    public Iterable<Subcategory> getAllSubcategories() {
        return subcategoryRepository.findAll();
    }

    /** Read-only projection of a packet's creator; null for anonymous/legacy packets. */
    @SchemaMapping(typeName = "Packet", field = "owner")
    public PacketOwnerDto owner(Packet packet) {
        return packet.getOwnerId() == null
                ? null
                : new PacketOwnerDto(packet.getOwnerId(), packet.getOwnerDisplayName());
    }
}
