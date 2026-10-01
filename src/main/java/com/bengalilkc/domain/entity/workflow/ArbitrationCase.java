package com.bengalilkc.domain.entity.workflow;

import com.bengalilkc.domain.enums.ArbitrationStatus;
import com.bengalilkc.domain.enums.ArbitrationVerdict;
import com.bengalilkc.domain.enums.DisputeType;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.VerdictBy;
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
@Table(name = "arbitration_case")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArbitrationCase {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "submission_id", nullable = false, length = 64)
    private String submissionId;

    @Column(name = "claimant_id", nullable = false, length = 64)
    private String claimantId;

    @Column(name = "accused_id", nullable = false, length = 64)
    private String accusedId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dispute_type", nullable = false, length = 40)
    private DisputeType disputeType;

    @Column(name = "dispute_note", nullable = false, columnDefinition = "TEXT")
    private String disputeNote;

    @Column(name = "counter_claim", columnDefinition = "TEXT")
    private String counterClaim;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "response_deadline", nullable = false)
    private Instant responseDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ArbitrationStatus status;

    /** Comma-separated curator user ids excluded for conflict of interest. */
    @Column(name = "excluded_curator_ids", columnDefinition = "TEXT")
    private String excludedCuratorIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_verdict", length = 40)
    private ArbitrationVerdict finalVerdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict_by", length = 20)
    private VerdictBy verdictBy;

    @Column(name = "tie_broken_by", length = 64)
    private String tieBrokenBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "correction_applied", nullable = false)
    @Builder.Default
    private boolean correctionApplied = false;

    @Column(name = "correction_note", columnDefinition = "TEXT")
    private String correctionNote;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "pre_arbitration_state", length = 40)
    private EntryState preArbitrationState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (openedAt == null) {
            openedAt = now;
        }
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
