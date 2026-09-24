package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SenseEntity;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SenseRepository extends JpaRepository<SenseEntity, String> {
    List<SenseEntity> findBySynsetId(String synsetId);

    List<SenseEntity> findByLexicalEntryId(String lexicalEntryId);

    /** Prefix search by lemma for Quick Input autocomplete (sense-rel targets). */
    @Query(
            """
            select s from SenseEntity s
            join fetch s.lexicalEntry e
            join fetch s.synset syn
            where lower(e.writtenForm) like lower(concat(:prefix, '%'))
              and (:pos is null or e.partOfSpeech = :pos)
            order by e.writtenForm asc
            """)
    List<SenseEntity> findForWordLookup(
            @Param("prefix") String prefix, @Param("pos") PartOfSpeech pos, Pageable pageable);

    /** Exact lemma+POS already linked to a given synset (lexicon-level exact duplicate). */
    @Query(
            """
            select (count(s) > 0) from SenseEntity s
            join s.lexicalEntry e
            where e.writtenForm = :writtenForm
              and e.partOfSpeech = :pos
              and s.synset.id = :synsetId
            """)
    boolean existsByLemmaPosAndSynset(
            @Param("writtenForm") String writtenForm,
            @Param("pos") PartOfSpeech pos,
            @Param("synsetId") String synsetId);

    /** Same lemma+POS already exists in the lexicon (any synset) — near-duplicate / polysemy. */
    @Query(
            """
            select (count(s) > 0) from SenseEntity s
            join s.lexicalEntry e
            where e.writtenForm = :writtenForm and e.partOfSpeech = :pos
            """)
    boolean existsByLemmaAndPos(
            @Param("writtenForm") String writtenForm, @Param("pos") PartOfSpeech pos);

    @Query(
            """
            select s from SenseEntity s
            join fetch s.synset syn
            join s.lexicalEntry e
            where e.writtenForm = :writtenForm and e.partOfSpeech = :pos
            """)
    List<SenseEntity> findByLemmaAndPos(
            @Param("writtenForm") String writtenForm, @Param("pos") PartOfSpeech pos);
}
