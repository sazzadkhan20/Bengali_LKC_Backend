package com.bengalilkc.repository.admin;

import com.bengalilkc.domain.entity.admin.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
}
