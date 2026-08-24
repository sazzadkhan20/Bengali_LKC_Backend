package com.bengalilkc.repository.identity;

import com.bengalilkc.domain.entity.identity.Credential;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialRepository extends JpaRepository<Credential, String> {
    Optional<Credential> findByUsernameIgnoreCase(String username);
    Optional<Credential> findByUserId(String userId);
    boolean existsByUsernameIgnoreCase(String username);
}
