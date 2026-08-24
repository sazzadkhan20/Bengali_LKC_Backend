package com.bengalilkc.repository.identity;

import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.enums.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    Optional<AppUser> findByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    List<AppUser> findByRoleAndActiveTrueAndSuspendedFalse(Role role);
    List<AppUser> findByRoleAndActiveTrue(Role role);
    List<AppUser> findAllByOrderByJoinedAtAsc();
}
