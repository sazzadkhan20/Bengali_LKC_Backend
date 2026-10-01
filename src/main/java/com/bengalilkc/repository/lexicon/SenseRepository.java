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

    /** Member lemmas for many synsets in one query: rows of [synsetId, writtenForm]. */
    @Query(
            """
            select s.synset.id, e.writtenForm from SenseEntity s
            join s.lexicalEntry e
            where s.synset.id in :synsetIds
            order by s.synset.id, e.writtenForm
            """)
    List<Object[]> findLemmasBySynsetIds(@Param("synsetIds") java.util.Collection<String> synsetIds);

    /** Display rows for sense-relation targets: [senseId, writtenForm, pos, synsetId, synsetDefinition]. */
    @Query(
            """
            select s.id, e.writtenForm, e.partOfSpeech, syn.id, syn.definitionText from SenseEntity s
            join s.lexicalEntry e
            join s.synset syn
            where s.id in :senseIds
            """)
    List<Object[]> findSummariesByIds(@Param("senseIds") java.util.Collection<String> senseIds);

    List<SenseEntity> findByLexicalEntryId(String lexicalEntryId);

    /** Prefix search on the normalised form key; pass a prefix built with {@code BengaliText.key}. */
    @Query(
            """
            select s from SenseEntity s
            join fetch s.lexicalEntry e
            join fetch s.synset syn
            where e.formKey like concat(:prefix, '%')
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
