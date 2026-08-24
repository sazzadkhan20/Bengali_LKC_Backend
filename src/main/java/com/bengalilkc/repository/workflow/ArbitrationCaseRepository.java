package com.bengalilkc.repository.workflow;

import com.bengalilkc.domain.entity.workflow.ArbitrationCase;
import com.bengalilkc.domain.enums.ArbitrationStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArbitrationCaseRepository extends JpaRepository<ArbitrationCase, String> {
    List<ArbitrationCase> findByStatus(ArbitrationStatus status);
    List<ArbitrationCase> findBySubmissionId(String submissionId);
    List<ArbitrationCase> findAllByOrderByOpenedAtDesc();
}
