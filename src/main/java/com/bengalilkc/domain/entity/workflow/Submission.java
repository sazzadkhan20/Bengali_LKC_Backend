package com.bengalilkc.domain.entity.workflow;

import com.bengalilkc.domain.enums.DefinitionLanguage;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import com.bengalilkc.domain.enums.SynsetAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "submission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Submission {

    @Id
    @Column(length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EntryState state;

    @Column(name = "contributor_id", nullable = false, length = 64)
    private String contributorId;

    @Column(name = "assigned_to", length = 64)
    private String assignedTo;

    @Column(name = "written_form", nullable = false, columnDefinition = "TEXT")
    private String writtenForm;

    @Column(name = "part_of_speech", nullable = false, length = 5)
    private PartOfSpeech partOfSpeech;

    @Column(name = "synset_action", nullable = false, length = 10)
    private SynsetAction synsetAction;

    @Column(name = "synset_id", length = 100)
    private String synsetId;

    @Column(name = "ili_code", length = 50)
    private String iliCode;

    @Column(name = "definition_language", length = 5)
    private DefinitionLanguage definitionLanguage;

    @Column(name = "definition_text", columnDefinition = "TEXT")
    private String definitionText;

    @Column(name = "sense_relations_json", columnDefinition = "TEXT")
    private String senseRelationsJson;

    @Column(name = "synset_relations_json", columnDefinition = "TEXT")
    private String synsetRelationsJson;

    @Column(name = "reviewer_note", columnDefinition = "TEXT")
    private String reviewerNote;

    @Column(name = "editor_comment", columnDefinition = "TEXT")
    private String editorComment;

    @Column(name = "cle_comment", columnDefinition = "TEXT")
    private String cleComment;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "validated_by", length = 64)
    private String validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "reward_eligible", nullable = false)
    @Builder.Default
    private boolean rewardEligible = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
