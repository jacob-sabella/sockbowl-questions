package com.soulsoftworks.sockbowlquestions.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.util.Objects;

@Entity
@Table(name = "tossups", schema = "public", catalog = "postgres")
@Data
public class Tossup {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;
    @Basic
    @Column(name = "question")
    private String question;
    @Basic
    @Column(name = "subcategory_id")
    private int subcategoryId;
    @Basic
    @Column(name = "answer")
    private String answer;
    @ManyToOne
    @JoinColumn(name = "subcategory_id", referencedColumnName = "id", nullable = false, insertable=false, updatable=false)
    @ToString.Exclude
    private Subcategory subcategory;
}
