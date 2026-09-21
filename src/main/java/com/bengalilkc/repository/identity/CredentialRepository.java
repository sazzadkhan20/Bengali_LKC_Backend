package com.bengalilkc.repository.identity;

import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.Role;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CredentialRepository extends JpaRepository<Credential, String> {
    Optional<Credential> findByUsernameIgnoreCase(String username);
    Optional<Credential> findByUserId(String userId);
    boolean existsByUsernameIgnoreCase(String username);

    Page<Credential> findAllByOrderByIssuedAtDesc(Pageable pageable);

    @Query(
            """
            SELECT c FROM Credential c, AppUser u
            WHERE u.id = c.userId
              AND (
                :q IS NULL OR :q = ''
                OR LOWER(c.username) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(u.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
              )
              AND (:role IS NULL OR u.role = :role)
            ORDER BY c.issuedAt DESC
            """)
    Page<Credential> search(
            @Param("q") String q, @Param("role") Role role, Pageable pageable);
}
