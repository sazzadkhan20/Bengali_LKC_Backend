package com.bengalilkc.domain.entity.lexicon;

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
@Table(name = "lexical_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LexicalEntryEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "lexicon_id", nullable = false, length = 100)
    private String lexiconId;

    @Column(name = "written_form", nullable = false, columnDefinition = "TEXT")
    private String writtenForm;

    
    @Column(name = "part_of_speech", nullable = false, length = 5)
    private PartOfSpeech partOfSpeech;

    @Column(nullable = false)
    @Builder.Default
    private boolean crowdsourced = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "lexicalEntry", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SenseEntity> senses = new ArrayList<>();

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
