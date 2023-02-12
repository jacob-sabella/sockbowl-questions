package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

@Data
public class BonusPartPK implements Serializable {
    @Column(name = "bonus_id")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int bonusId;
    @Column(name = "number")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int number;
}
