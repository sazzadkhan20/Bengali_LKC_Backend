package com.bengalilkc.domain.entity.community;

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
@Table(name = "post_reply")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostReply {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "post_id", nullable = false, length = 64)
    private String postId;

    @Column(name = "author_id", nullable = false, length = 64)
    private String authorId;

    @Column(name = "text_body", nullable = false, length = 200)
    private String textBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
