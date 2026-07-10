package com.soulsoftworks.sockbowlquestions.dto;

/** Read-only GraphQL projection of a packet's owner (see {@code PacketOwner} in schema.graphqls). */
public record PacketOwnerDto(String id, String name) {
}
