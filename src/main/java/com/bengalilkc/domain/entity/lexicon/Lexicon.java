package com.bengalilkc.domain.entity.lexicon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "lexicon")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lexicon {

    @Id
    @Column(length = 100)
    private String id;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String license;

    @Column(length = 20)
    private String version;

    @Column(name = "dc_source", columnDefinition = "TEXT")
    private String dcSource;

    @Column(name = "dc_description", columnDefinition = "TEXT")
    private String dcDescription;

    @Column(name = "dc_publisher", length = 255)
    private String dcPublisher;

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
