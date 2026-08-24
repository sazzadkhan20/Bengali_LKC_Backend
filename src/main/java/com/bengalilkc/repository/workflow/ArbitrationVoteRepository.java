package com.bengalilkc.repository.workflow;

import com.bengalilkc.domain.entity.workflow.ArbitrationVote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArbitrationVoteRepository extends JpaRepository<ArbitrationVote, Long> {
    List<ArbitrationVote> findByCaseId(String caseId);
    Optional<ArbitrationVote> findByCaseIdAndCuratorId(String caseId, String curatorId);
    boolean existsByCaseIdAndCuratorId(String caseId, String curatorId);
}
