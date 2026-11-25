package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SynsetRepository extends JpaRepository<SynsetEntity, String> {
    Optional<SynsetEntity> findByIli(String ili);

    /** True only for real Princeton ILI codes (multiple {@code n/a} synsets are allowed). */
    @Query("""
            select (count(s) > 0) from SynsetEntity s
            where s.ili = :ili and lower(s.ili) <> 'n/a'
            """)
    boolean existsByIli(String ili);

    @Query("select s.ili from SynsetEntity s where s.ili in :ilis")
    List<String> findExistingIlis(@Param("ilis") java.util.Collection<String> ilis);

    List<SynsetEntity> findByIdStartingWith(String prefix, Pageable pageable);

    /** Synsets with any definition (Bengali or English) containing {@code q}; {@code pos} is a code or null. */
    @Query(value = """
        select s.* from synset s
        where exists (select 1 from synset_definition d
                      where d.synset_id = s.id and lower(d.text) like lower(concat('%', :q, '%')))
          and (cast(:pos as varchar) is null or s.part_of_speech = cast(:pos as varchar))
        order by s.id
        """, nativeQuery = true)
    List<SynsetEntity> searchByDefinition(
            @Param("q") String q, @Param("pos") String pos, Pageable pageable);

    List<SynsetEntity> findByPartOfSpeech(PartOfSpeech pos);
}
