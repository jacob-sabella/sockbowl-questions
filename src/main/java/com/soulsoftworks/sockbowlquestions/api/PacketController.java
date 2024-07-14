package com.soulsoftworks.sockbowlquestions.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.soulsoftworks.sockbowlquestions.config.ExclusionStrategies;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/packets/")
public class PacketController {

    private final Gson gson;
    private final PacketRepository packetRepository;

    public PacketController(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
        gson = new GsonBuilder().addSerializationExclusionStrategy(ExclusionStrategies.gsonExclusionStrategy).create();
    }

    @GetMapping(value = "/get/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getById(@PathVariable String id){
        return gson.toJson(packetRepository.getPacketById(id));
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public String searchByName(@RequestParam String name) {
        List<Packet> packets = packetRepository.searchByName(name);
        return gson.toJson(packets);
    }
}
