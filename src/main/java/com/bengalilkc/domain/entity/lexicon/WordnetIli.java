package com.bengalilkc.domain.entity.lexicon;

import com.bengalilkc.domain.enums.PartOfSpeech;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wordnet_ili")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WordnetIli {

    @Id
    @Column(length = 50)
    private String ili;

    
    @Column(nullable = false, length = 5)
    private PartOfSpeech pos;

    @Column(name = "words_csv", columnDefinition = "TEXT")
    private String wordsCsv;

    @Column(columnDefinition = "TEXT")
    private String gloss;
}
