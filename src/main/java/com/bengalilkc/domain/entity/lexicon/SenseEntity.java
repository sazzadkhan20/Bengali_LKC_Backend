package com.bengalilkc.domain.entity.lexicon;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
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
@Table(name = "sense")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SenseEntity {

    @Id
    @Column(length = 100)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lexical_entry_id", nullable = false)
    private LexicalEntryEntity lexicalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "synset_id", nullable = false)
    private SynsetEntity synset;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** XML (imported) or USER (promoted from a validated submission); written only by the database/promotion. */
    @Column(name = "origin", insertable = false, updatable = false, length = 10)
    private String origin;

    @OneToMany(mappedBy = "sourceSense", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SenseRelationEntity> relations = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
