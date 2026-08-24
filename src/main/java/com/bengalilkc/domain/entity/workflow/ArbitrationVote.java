package com.bengalilkc.domain.entity.workflow;

import com.bengalilkc.domain.enums.ArbitrationVerdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "arbitration_vote")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArbitrationVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, length = 64)
    private String caseId;

    @Column(name = "curator_id", nullable = false, length = 64)
    private String curatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ArbitrationVerdict verdict;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "correction_note", columnDefinition = "TEXT")
    private String correctionNote;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    @PrePersist
    void prePersist() {
        if (votedAt == null) {
            votedAt = Instant.now();
        }
    }
}
