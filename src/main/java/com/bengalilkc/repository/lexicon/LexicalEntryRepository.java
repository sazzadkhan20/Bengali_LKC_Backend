package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.LexicalEntryEntity;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LexicalEntryRepository extends JpaRepository<LexicalEntryEntity, String> {

    List<LexicalEntryEntity> findByWrittenFormStartingWithOrderByWrittenFormAsc(String prefix, Pageable pageable);

    @Query("""
        select e from LexicalEntryEntity e
        where e.writtenForm like concat(:letter, '%')
        order by e.writtenForm asc
        """)
    List<LexicalEntryEntity> findByInitialLetter(String letter, Pageable pageable);

    List<LexicalEntryEntity> findByPartOfSpeech(PartOfSpeech pos);

    long countByCrowdsourcedTrue();
}
