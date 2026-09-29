package com.bengalilkc.repository.workflow;

import com.bengalilkc.domain.entity.workflow.Submission;
import com.bengalilkc.domain.enums.EntryState;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubmissionRepository extends JpaRepository<Submission, String> {
    List<Submission> findByState(EntryState state);
    List<Submission> findByContributorId(String contributorId);
    List<Submission> findByStateIn(List<EntryState> states);
    long countByContributorIdAndCreatedAtAfter(String contributorId, Instant after);

    @Query("""
        select s from Submission s
        where s.writtenForm = :writtenForm and s.partOfSpeech = :pos and s.state <> com.bengalilkc.domain.enums.EntryState.REJECTED
        """)
    List<Submission> findActiveByLemmaAndPos(String writtenForm, PartOfSpeech pos);

    List<Submission> findByStateOrderByUpdatedAtDesc(EntryState state);
    long countByState(EntryState state);
    long countByContributorIdAndState(String contributorId, EntryState state);
}
