package com.bengalilkc.repository.community;

import com.bengalilkc.domain.entity.community.CommunityPost;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, String> {
    List<CommunityPost> findAllByOrderByCreatedAtDesc();
    List<CommunityPost> findByHiddenFalseOrderByCreatedAtDesc();
    List<CommunityPost> findByAuthorIdOrderByCreatedAtDesc(String authorId);
}
