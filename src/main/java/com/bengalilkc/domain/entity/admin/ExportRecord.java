package com.bengalilkc.domain.entity.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "export_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportRecord {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "synset_count", nullable = false)
    private int synsetCount;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @PrePersist
    void prePersist() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
