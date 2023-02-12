package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "events", schema = "public", catalog = "postgres")
@Data
public class Event {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;
    @Basic
    @Column(name = "name")
    private String name;
    @Basic
    @Column(name = "year")
    private Integer year;
    @Basic
    @Column(name = "location")
    private String location;
    @Basic
    @Column(name = "imported")
    private boolean imported;
    @OneToMany(mappedBy = "event")
    @ToString.Exclude
    private List<EventPacket> eventPackets;
}
