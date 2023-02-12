package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.util.List;

@Entity
@Table(name = "bonuses", schema = "public", catalog = "postgres")
@Data
public class Bonus {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;
    @Basic
    @Column(name = "subcategory_id")
    private int subcategoryId;
    @Basic
    @Column(name = "preamble")
    private String preamble;

    @OneToMany(mappedBy = "bonusId")
    @ToString.Exclude
    private List<BonusPart> bonusParts;
    @ManyToOne
    @JoinColumn(name = "subcategory_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    private Subcategory subcategory;
}
