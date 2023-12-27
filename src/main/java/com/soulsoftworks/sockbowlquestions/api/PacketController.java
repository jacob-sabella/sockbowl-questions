package com.soulsoftworks.sockbowlquestions.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.soulsoftworks.sockbowlquestions.config.ExclusionStrategies;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/api/v1/packets/")
public class PacketController {

    private final Gson gson;
    private PacketRepository packetRepository;

    public PacketController(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
        gson = new GsonBuilder().addSerializationExclusionStrategy(ExclusionStrategies.gsonExclusionStrategy).create();
    }

    @GetMapping(value = "/get/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String getById(@PathVariable int id){
        return gson.toJson(packetRepository.getPacketById(id));
    }

}
