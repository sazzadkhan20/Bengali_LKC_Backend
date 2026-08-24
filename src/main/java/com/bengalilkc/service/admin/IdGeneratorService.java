package com.bengalilkc.service.admin;

import com.bengalilkc.domain.entity.admin.IdSequence;
import com.bengalilkc.repository.admin.IdSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdGeneratorService {

    private final IdSequenceRepository idSequenceRepository;

    @Transactional
    public long nextValue(String sequenceName) {
        IdSequence seq = idSequenceRepository.findByNameForUpdate(sequenceName)
                .orElseGet(() -> {
                    IdSequence created = IdSequence.builder().name(sequenceName).nextValue(1L).build();
                    return idSequenceRepository.save(created);
                });
        long current = seq.getNextValue();
        seq.setNextValue(current + 1);
        idSequenceRepository.save(seq);
        return current;
    }

    @Transactional
    public String nextPrefixed(String sequenceName, String prefix) {
        return prefix + nextValue(sequenceName);
    }
}
