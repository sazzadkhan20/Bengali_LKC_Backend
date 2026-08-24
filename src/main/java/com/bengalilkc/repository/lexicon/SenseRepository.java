package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SenseRepository extends JpaRepository<SenseEntity, String> {
    List<SenseEntity> findBySynsetId(String synsetId);
    List<SenseEntity> findByLexicalEntryId(String lexicalEntryId);

    @Query("""
        select s from SenseEntity s
        join fetch s.lexicalEntry e
        join fetch s.synset syn
        where e.writtenForm like concat(:prefix, '%')
        order by e.writtenForm
        """)
    List<SenseEntity> findForWordLookup(String prefix);
}
