package com.soulsoftworks.sockbowlquestions.models;

import com.soulsoftworks.sockbowlquestions.config.GsonExclude;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

@Entity
@Table(name = "event_packets", schema = "public", catalog = "postgres")
@IdClass(EventPacketPK.class)
@Data
public class EventPacket {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "event_id")
    private int eventId;
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "packet_id")
    private int packetId;
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "round")
    private int round;
    @ManyToOne
    @JoinColumn(name = "event_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    @GsonExclude
    private Event event;
    @ManyToOne
    @JoinColumn(name = "packet_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    private Packet packet;
}
