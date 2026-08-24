package com.bengalilkc.repository.lexicon;

import com.bengalilkc.domain.entity.lexicon.SenseRelationType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SenseRelationTypeRepository extends JpaRepository<SenseRelationType, Long> {
    Optional<SenseRelationType> findByRelationName(String relationName);
}
