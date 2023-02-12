package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

@Data
public class EventPacketPK implements Serializable {
    @Column(name = "event_id")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int eventId;
    @Column(name = "packet_id")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int packetId;
    @Column(name = "round")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int round;
}
