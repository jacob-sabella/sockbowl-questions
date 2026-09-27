package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.api.input.PacketFilterInput;
import com.soulsoftworks.sockbowlquestions.dto.PacketPageDto;
import com.soulsoftworks.sockbowlquestions.repository.PacketSummaryRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * The {@code packets} list query (M3 Q5, plan 3.1.9, PB-19). Open: no
 * {@code @PreAuthorize}, since the projection is answer-free and every row is already
 * filtered by {@link PacketSummaryRepository} through {@code PacketReadPolicy} (guests and
 * anonymous callers get an empty-filtered, PUBLISHED-only page rather than being denied).
 */
@Controller
public class PacketQueryController {

    private final PacketSummaryRepository packetSummaryRepository;

    public PacketQueryController(PacketSummaryRepository packetSummaryRepository) {
        this.packetSummaryRepository = packetSummaryRepository;
    }

    @QueryMapping
    public PacketPageDto packets(@Argument PacketFilterInput filter, @Argument Integer page, @Argument Integer size) {
        return packetSummaryRepository.find(filter, page, size);
    }
}
