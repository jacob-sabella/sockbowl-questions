package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.dto.PacketOwnerDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Category;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;

@Controller
public class GraphQLController {

    private final PacketRepository packetRepository;
    private final DifficultyRepository difficultyRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final PacketReadPolicy readPolicy;

    public GraphQLController(PacketRepository packetRepository,
                              DifficultyRepository difficultyRepository,
                              CategoryRepository categoryRepository,
                              SubcategoryRepository subcategoryRepository,
                              PacketReadPolicy readPolicy) {
        this.readPolicy = readPolicy;
        this.packetRepository = packetRepository;
        this.difficultyRepository = difficultyRepository;
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
    }

    /**
     * Fetches the packets the caller may see (D2).
     *
     * <p>No {@code @PreAuthorize}: guests and anonymous callers (including anonymous
     * game hosting) can still read packets, but only through {@link PacketReadPolicy}:
     * drafts are left out unless the caller owns them or may read every packet, and
     * callers without full-read rights get the answer-free projection.
     */
    @QueryMapping
    public List<Packet> getAllPackets() {
        Authentication auth = PacketReadPolicy.currentAuthentication();
        Iterable<Packet> candidates = readPolicy.canReadEveryPacket(auth)
                ? packetRepository.findAll()
                : packetRepository.findAllById(packetRepository.findVisiblePacketIds(
                        readPolicy.publiclyReadableVisibilities(), readPolicy.legacyVisibility(),
                        readPolicy.callerId(auth)));
        return project(auth, candidates);
    }

    /**
     * Fetches a packet by its ID, or {@code null} when it doesn't exist or the caller
     * may not see it (the two are indistinguishable, so there's no existence oracle).
     */
    @QueryMapping
    public Packet getPacketById(@Argument String id) {
        Authentication auth = PacketReadPolicy.currentAuthentication();
        Packet packet = packetRepository.findById(id).orElse(null);
        if (!readPolicy.canSee(auth, packet)) {
            return null;
        }
        return PacketProjection.forCaller(readPolicy, auth, packet);
    }

    /** Name search, with the same visibility and projection rules as {@link #getAllPackets()}. */
    @QueryMapping
    public List<Packet> searchPacketsByName(@Argument String name) {
        Authentication auth = PacketReadPolicy.currentAuthentication();
        List<Packet> candidates = readPolicy.canReadEveryPacket(auth)
                ? packetRepository.searchByName(name)
                : packetRepository.searchVisibleByName(name, readPolicy.publiclyReadableVisibilities(),
                        readPolicy.legacyVisibility(), readPolicy.callerId(auth));
        return project(auth, candidates);
    }

    /** Re-checks {@code canSee} in Java (the query filter is an optimization) and projects. */
    private List<Packet> project(Authentication auth, Iterable<Packet> packets) {
        List<Packet> out = new ArrayList<>();
        for (Packet packet : packets) {
            if (readPolicy.canSee(auth, packet)) {
                out.add(PacketProjection.forCaller(readPolicy, auth, packet));
            }
        }
        return out;
    }

    /**
     * Fetches all difficulties. No {@code @PreAuthorize}: the taxonomy is public.
     *
     * @return Iterable of Difficulty
     */
    @QueryMapping
    public Iterable<Difficulty> getAllDifficulties() {
        return difficultyRepository.findAll();
    }

    /**
     * Fetches all categories. No {@code @PreAuthorize}: the taxonomy is public.
     *
     * @return Iterable of Category
     */
    @QueryMapping
    public Iterable<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    /**
     * Fetches all subcategories. No {@code @PreAuthorize}: the taxonomy is public.
     *
     * @return Iterable of Subcategory
     */
    @QueryMapping
    public Iterable<Subcategory> getAllSubcategories() {
        return subcategoryRepository.findAll();
    }

    /** Legacy nodes have no stored visibility; they are exposed as their effective value. */
    @SchemaMapping(typeName = "Packet", field = "visibility")
    public PacketVisibility visibility(Packet packet) {
        return PacketVisibility.effective(packet.getVisibility());
    }

    /** Read-only projection of a packet's creator; null for anonymous/legacy packets. */
    @SchemaMapping(typeName = "Packet", field = "owner")
    public PacketOwnerDto owner(Packet packet) {
        return packet.getOwnerId() == null
                ? null
                : new PacketOwnerDto(packet.getOwnerId(), packet.getOwnerDisplayName());
    }
}
