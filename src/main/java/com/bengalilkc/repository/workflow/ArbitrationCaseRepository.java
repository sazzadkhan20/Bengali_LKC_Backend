package com.bengalilkc.repository.workflow;

import com.bengalilkc.domain.entity.workflow.ArbitrationCase;
import com.bengalilkc.domain.enums.ArbitrationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArbitrationCaseRepository extends JpaRepository<ArbitrationCase, String> {
    List<ArbitrationCase> findByStatus(ArbitrationStatus status);
    List<ArbitrationCase> findBySubmissionId(String submissionId);
    List<ArbitrationCase> findAllByOrderByOpenedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ArbitrationCase c where c.id = :id")
    Optional<ArbitrationCase> findForUpdate(@Param("id") String id);

    @Query("select c.id from ArbitrationCase c where c.status = :status and c.responseDeadline <= :now"
            + " order by c.responseDeadline")
    List<String> findIdsWithResponseWindowDue(@Param("status") ArbitrationStatus status, @Param("now") Instant now);

    @Query("select c.id from ArbitrationCase c where c.status = :status and c.votingDeadline <= :now"
            + " order by c.votingDeadline")
    List<String> findIdsWithVotingDue(@Param("status") ArbitrationStatus status, @Param("now") Instant now);
}
