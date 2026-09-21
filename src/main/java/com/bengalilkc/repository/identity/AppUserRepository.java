package com.bengalilkc.repository.identity;

import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.enums.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    Optional<AppUser> findByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    List<AppUser> findByRoleAndActiveTrueAndSuspendedFalse(Role role);
    List<AppUser> findByRoleAndActiveTrue(Role role);
    List<AppUser> findAllByOrderByJoinedAtAsc();

    /**
     * Backend-driven user search for User Management.
     * {@code status}: null/ALL | ACTIVE | SUSPENDED | INACTIVE
     */
    @Query(
            """
            SELECT u FROM AppUser u
            WHERE (
                :q IS NULL OR :q = ''
                OR LOWER(u.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(u.username) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
              )
              AND (:role IS NULL OR u.role = :role)
              AND (
                :status IS NULL OR :status = '' OR :status = 'ALL'
                OR (:status = 'ACTIVE' AND u.active = true AND u.suspended = false)
                OR (:status = 'SUSPENDED' AND u.suspended = true)
                OR (:status = 'INACTIVE' AND u.active = false)
              )
            ORDER BY u.joinedAt DESC
            """)
    Page<AppUser> search(
            @Param("q") String q,
            @Param("role") Role role,
            @Param("status") String status,
            Pageable pageable);
}
