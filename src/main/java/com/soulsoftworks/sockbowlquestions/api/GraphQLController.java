package com.soulsoftworks.sockbowlquestions.api;

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
import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * Fetches all difficulties.
     *
     * @return Iterable of Difficulty
     */
    @QueryMapping
    @PreAuthorize("hasAuthority('packet:read')")
    public Iterable<Difficulty> getAllDifficulties() {
        return difficultyRepository.findAll();
    }

    /**
     * Fetches all categories.
     *
     * @return Iterable of Category
     */
    @QueryMapping
    @PreAuthorize("hasAuthority('packet:read')")
    public Iterable<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    /**
     * Fetches all subcategories.
     *
     * @return Iterable of Subcategory
     */
    @QueryMapping
    @PreAuthorize("hasAuthority('packet:read')")
    public Iterable<Subcategory> getAllSubcategories() {
        return subcategoryRepository.findAll();
    }
}