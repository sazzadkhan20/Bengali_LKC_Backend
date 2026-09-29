package com.bengalilkc.repository.workflow;

import com.bengalilkc.domain.entity.workflow.StateTransition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateTransitionRepository extends JpaRepository<StateTransition, Long> {
    List<StateTransition> findBySubmissionIdOrderByCreatedAtAsc(String submissionId);

    List<StateTransition> findBySubmissionIdInOrderByCreatedAtAsc(Collection<String> submissionIds);
}
