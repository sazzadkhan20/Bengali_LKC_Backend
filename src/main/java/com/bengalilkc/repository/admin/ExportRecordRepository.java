package com.bengalilkc.repository.admin;

import com.bengalilkc.domain.entity.admin.ExportRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportRecordRepository extends JpaRepository<ExportRecord, String> {
    List<ExportRecord> findAllByOrderByTimestampDesc();
}
