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
@Table(name = "synset_relation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SynsetRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_synset_id", nullable = false)
    private SynsetEntity sourceSynset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_synset_id", nullable = false)
    private SynsetEntity targetSynset;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "relation_type_id", nullable = false)
    private SynsetRelationType relationType;
}
