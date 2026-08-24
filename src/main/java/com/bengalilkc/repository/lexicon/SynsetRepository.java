package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SynsetEntity;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SynsetRepository extends JpaRepository<SynsetEntity, String> {
    Optional<SynsetEntity> findByIli(String ili);
    boolean existsByIli(String ili);
    List<SynsetEntity> findByIdStartingWith(String prefix, Pageable pageable);

    @Query("""
        select s from SynsetEntity s
        where lower(s.definitionText) like lower(concat('%', :q, '%'))
        """)
    List<SynsetEntity> searchByDefinition(String q, Pageable pageable);

    List<SynsetEntity> findByPartOfSpeech(PartOfSpeech pos);
}
