package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SynsetRelationType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynsetRelationTypeRepository extends JpaRepository<SynsetRelationType, Long> {
    Optional<SynsetRelationType> findByRelationName(String relationName);
}
