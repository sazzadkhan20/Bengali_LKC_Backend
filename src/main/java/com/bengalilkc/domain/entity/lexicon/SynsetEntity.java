package com.bengalilkc.domain.entity.lexicon;

import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.PartOfSpeech;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "synset")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SynsetEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "lexicon_id", nullable = false, length = 100)
    private String lexiconId;

    @Column(nullable = false, length = 50)
    private String ili;

    
    @Column(name = "part_of_speech", nullable = false, length = 5)
    private PartOfSpeech partOfSpeech;

    @Column(nullable = false)
    @Builder.Default
    private boolean lexicalized = true;

    /**
     * Primary gloss (Bengali first, else English) — maintained by a database trigger from
     * {@code synset_definition}, which holds every definition. Read-only here.
     */
    @Column(name = "definition_language", length = 5, insertable = false, updatable = false)
    private DefinitionLanguage definitionLanguage;

    @Column(name = "definition_text", columnDefinition = "TEXT", insertable = false, updatable = false)
    private String definitionText;

    @Column(nullable = false)
    @Builder.Default
    private boolean crowdsourced = false;

    /** XML (imported) or USER (promoted from a validated submission); written only by the database/promotion. */
    @Column(name = "origin", insertable = false, updatable = false, length = 10)
    private String origin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "synset", fetch = FetchType.LAZY)
    @Builder.Default
    private List<SenseEntity> senses = new ArrayList<>();

    @OneToMany(mappedBy = "sourceSynset", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SynsetRelationEntity> relations = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
