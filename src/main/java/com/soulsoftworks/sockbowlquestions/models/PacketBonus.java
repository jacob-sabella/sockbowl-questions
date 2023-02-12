package com.soulsoftworks.sockbowlquestions.models;

import com.soulsoftworks.sockbowlquestions.config.GsonExclude;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

@Entity
@Table(name = "packet_bonuses", schema = "public", catalog = "postgres")
@IdClass(PacketBonusPK.class)
@Data
public class PacketBonus {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "packet_id")
    private int packetId;
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "bonus_id")
    private int bonusId;
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
    @JoinColumn(name = "bonus_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    private Bonus bonus;
}
