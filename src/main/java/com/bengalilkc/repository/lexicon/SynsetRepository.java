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

    @Query("""
        select s from SynsetEntity s
        where lower(s.definitionText) like lower(concat('%', :q, '%'))
          and (:pos is null or s.partOfSpeech = :pos)
        """)
    List<SynsetEntity> searchByDefinition(
            @Param("q") String q, @Param("pos") PartOfSpeech pos, Pageable pageable);

    List<SynsetEntity> findByPartOfSpeech(PartOfSpeech pos);
}
