package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.time.Instant;
import java.util.Optional;

/**
 * Is a Keycloak subject banned (D8, AB-01)? The real implementation comes from
 * WP-G4 (backed by {@code BanStatusCache}); until then, and with auth off,
 * {@link LimitsFallbackAutoConfiguration} registers a no-op. Implementations
 * must be cached and fail open.
 */
public interface SubjectBanChecker {

    /** A ban's reason and expiry ({@code null} = permanent). */
    record SubjectBan(String reason, Instant expiresAt) {
    }

    Optional<SubjectBan> findActiveBan(String sub);

    /** Throws {@link SubjectBannedException} when the subject is banned. */
    default void ensureNotBanned(String sub) {
        if (sub == null) {
            return;
        }
        findActiveBan(sub).ifPresent(ban -> {
            throw SubjectBannedException.from(ban);
        });
    }

    SubjectBanChecker NONE = sub -> Optional.empty();
}
