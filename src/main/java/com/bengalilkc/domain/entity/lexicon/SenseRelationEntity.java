package com.bengalilkc.domain.entity.lexicon;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sense_relation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SenseRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sense_id_1", nullable = false)
    private SenseEntity sourceSense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sense_id_2", nullable = false)
    private SenseEntity targetSense;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "relation_type_id", nullable = false)
    private SenseRelationType relationType;
}
