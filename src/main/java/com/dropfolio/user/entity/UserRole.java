package com.dropfolio.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Many-to-many junction, ERD.md §2.3. MVP practically 1 user = 1 role, but structure supports
 * many-to-many so {@code PREMIUM} can be added without a schema migration.
 *
 * FK behavior (enforced at the DB/migration level, not repeated here): {@code ON DELETE CASCADE}
 * from users, {@code ON DELETE NO ACTION} from roles.
 */
@Entity
@Table(name = "user_roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("roleId")
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public static UserRole of(User user, Role role) {
        UserRole ur = new UserRole();
        ur.setId(new UserRoleId(user.getId(), role.getId()));
        ur.setUser(user);
        ur.setRole(role);
        ur.setAssignedAt(Instant.now());
        return ur;
    }
}
