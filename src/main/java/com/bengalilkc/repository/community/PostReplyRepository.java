package com.bengalilkc.repository.community;

import com.bengalilkc.domain.entity.community.PostReply;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostReplyRepository extends JpaRepository<PostReply, String> {
    List<PostReply> findByPostIdOrderByCreatedAtAsc(String postId);
}
