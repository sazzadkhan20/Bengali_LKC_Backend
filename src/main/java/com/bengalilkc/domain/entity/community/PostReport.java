package com.bengalilkc.domain.entity.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "post_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, length = 64)
    private String postId;

    @Column(name = "reporter_id", nullable = false, length = 64)
    private String reporterId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @PrePersist
    void prePersist() {
        if (reportedAt == null) {
            reportedAt = Instant.now();
        }
    }
}
