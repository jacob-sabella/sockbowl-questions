package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

@Data
public class PacketTossupPK implements Serializable {
    @Column(name = "packet_id")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int packetId;
    @Column(name = "tossup_id")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int tossupId;
    @Column(name = "number")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int number;
}
