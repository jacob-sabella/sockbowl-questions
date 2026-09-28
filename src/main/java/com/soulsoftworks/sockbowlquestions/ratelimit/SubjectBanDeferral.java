package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.util.List;

/**
 * A component that enforces subject bans itself for some paths, so that
 * {@link RequestGuardFilter} answers them in that transport's own error format
 * instead of a 403 JSON body (WP-Q4: {@code POST /graphql} answers a GraphQL
 * error classified {@code BANNED}, plan m4-limits section 2.1).
 *
 * <p>The filter skips only the {@link SubjectBanChecker} step, and only for the
 * paths declared by a bean of this type that is actually present in the
 * context, so the filter can never stop checking a path that nothing else
 * checks. IP bans and rate limits are unaffected.
 */
public interface SubjectBanDeferral {

    /** Ant-style path patterns (within the application) whose subject-ban check this component performs. */
    List<String> deferredPaths();
}
