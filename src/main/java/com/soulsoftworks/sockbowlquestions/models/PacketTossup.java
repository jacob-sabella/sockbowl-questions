package com.soulsoftworks.sockbowlquestions.models;

import com.soulsoftworks.sockbowlquestions.config.GsonExclude;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

@Entity
@Table(name = "packet_tossups", schema = "public", catalog = "postgres")
@IdClass(PacketTossupPK.class)
@Data
public class PacketTossup {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "packet_id")
    private int packetId;
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "tossup_id")
    private int tossupId;
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "number")
    private int number;
    @ManyToOne
    @JoinColumn(name = "packet_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    @GsonExclude
    private Packet packet;
    @ManyToOne
    @JoinColumn(name = "tossup_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    private Tossup tossup;
}
