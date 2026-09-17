package com.dropfolio.user.repository;

import com.dropfolio.user.entity.RoleName;
import com.dropfolio.user.entity.UserRole;
import com.dropfolio.user.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    @Query("SELECT ur.role.name FROM UserRole ur WHERE ur.user.id = :userId")
    List<RoleName> findRoleNamesByUserId(@Param("userId") Long userId);
}
