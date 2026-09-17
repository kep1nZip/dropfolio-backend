package com.dropfolio.user.repository;

import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** M9 — GET /admin/dashboard "activeUsers". */
    long countByStatus(UserStatus status);
}
