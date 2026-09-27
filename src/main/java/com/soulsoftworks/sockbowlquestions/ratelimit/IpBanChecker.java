package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.time.Instant;
import java.util.Optional;

/**
 * Is a client address inside an active IP/CIDR ban (D8, AB-02)? The real
 * implementation comes from WP-G4 ({@code IpBanService}); until then, and with
 * auth off, {@link LimitsFallbackAutoConfiguration} registers a no-op.
 * Implementations must be cheap (in-memory) and fail open.
 */
public interface IpBanChecker {

    /**
     * @param rawAddress the un-truncated remote address (not the /64-normalized key)
     * @return the ban's expiry when the address is banned
     */
    Optional<Instant> findActiveBan(String rawAddress);

    /** Throws {@link IpBannedException} when the address is banned. */
    default void ensureNotBanned(String rawAddress) {
        findActiveBan(rawAddress).ifPresent(expiresAt -> {
            throw new IpBannedException(expiresAt);
        });
    }

    IpBanChecker NONE = rawAddress -> Optional.empty();
}
