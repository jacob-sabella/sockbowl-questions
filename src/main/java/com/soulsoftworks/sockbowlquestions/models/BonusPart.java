package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.util.Objects;

@Entity
@Table(name = "bonus_parts", schema = "public", catalog = "postgres")
@IdClass(BonusPartPK.class)
@Data
public class BonusPart {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "bonus_id")
    private int bonusId;
    @Basic
    @Column(name = "question")
    private String question;
    @Basic
    @Column(name = "answer")
    private String answer;
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "number")
    private int number;
}
