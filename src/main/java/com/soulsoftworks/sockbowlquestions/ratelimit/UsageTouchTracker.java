package com.soulsoftworks.sockbowlquestions.ratelimit;

/**
 * Records that an authenticated subject was just seen, for the admin usage view
 * ({@code usage:{sub}:meta} and {@code usage:{sub}:ips}, plan m4-limits
 * sections 2.1 and 2.4).
 *
 * <p>Called by {@link RequestGuardFilter} once per admitted request whose
 * subject is authenticated (guests are never passed), and by the post-auth
 * STOMP usage guard on CONNECT (WP-G3). The real implementation is WP-G5's
 * {@code usage.UsageTracker}; until it is present,
 * {@link LimitsFallbackAutoConfiguration} registers {@link #NONE}.
 *
 * <p>Implementations must be cheap on the request path: throttle writes
 * (at most once per {@code sockbowl.usage.touch-interval} per subject and
 * instance), never block on Redis, and never throw.
 */
@FunctionalInterface
public interface UsageTouchTracker {

    void touch(LimitSubject subject);

    UsageTouchTracker NONE = subject -> {
    };
}
