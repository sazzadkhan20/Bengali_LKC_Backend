package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.WordnetIli;
import com.bengalilkc.domain.enums.PartOfSpeech;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WordnetIliRepository extends JpaRepository<WordnetIli, String> {

    @Query("""
        select w from WordnetIli w
        where lower(w.wordsCsv) like lower(concat('%', :word, '%'))
          and (:pos is null or w.pos = :pos)
        """)
    List<WordnetIli> searchByWord(String word, PartOfSpeech pos, Pageable pageable);
}
