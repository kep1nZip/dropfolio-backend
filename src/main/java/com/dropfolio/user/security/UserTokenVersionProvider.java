package com.dropfolio.user.security;

import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Concrete {@link TokenVersionProvider} backed by {@link UserRepository} — registered here
 * (not in {@code common/}) per CLAUDE_CONTEXT.md §3 dependency rule: common depends on no
 * module, modules depend on common via interface injection.
 *
 * A DELETED user is treated as "not eligible to authenticate" even though the row still
 * physically exists (soft delete, ERD.md §6.1) — same effect as user-not-found from the
 * filter's perspective (stay anonymous), without leaking anything about deleted accounts.
 */
@Component
public class UserTokenVersionProvider implements TokenVersionProvider {

    private final UserRepository userRepository;

    public UserTokenVersionProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<Integer> currentTokenVersion(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getStatus() != UserStatus.DELETED)
                .map(u -> u.getTokenVersion());
    }
}
