package com.bengalilkc.repository.community;

import com.bengalilkc.domain.entity.community.PostReport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostReportRepository extends JpaRepository<PostReport, Long> {
    List<PostReport> findByPostId(String postId);
    boolean existsByPostIdAndReporterId(String postId, String reporterId);
}
