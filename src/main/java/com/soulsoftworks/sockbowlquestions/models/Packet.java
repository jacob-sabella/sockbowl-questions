package com.soulsoftworks.sockbowlquestions.models;

import lombok.Data;

import jakarta.persistence.*;
import lombok.ToString;

import java.util.List;

@Entity
@Table(name = "packets", schema = "public", catalog = "postgres")
@Data
public class Packet {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;
    @Basic
    @Column(name = "name")
    private String name;
    @OneToMany(mappedBy = "packet")
    @ToString.Exclude
    private List<PacketTossup> tossups;

    @OneToMany(mappedBy = "packet")
    @ToString.Exclude
    private List<PacketBonus> bonuses;
    @ManyToOne
    @JoinColumn(name = "difficulty_id", referencedColumnName = "id", nullable = false)
    private Difficulty difficulty;
}
