package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SynsetRelationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynsetRelationRepository extends JpaRepository<SynsetRelationEntity, Long> {
    List<SynsetRelationEntity> findBySourceSynsetId(String sourceSynsetId);
}
