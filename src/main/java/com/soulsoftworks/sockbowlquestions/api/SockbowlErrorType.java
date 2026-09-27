package com.soulsoftworks.sockbowlquestions.api;

import graphql.ErrorClassification;

/**
 * Sockbowl-specific GraphQL error classifications, reported in each error's
 * {@code extensions.classification} alongside Spring GraphQL's own
 * {@link org.springframework.graphql.execution.ErrorType} values
 * ({@code UNAUTHORIZED}, {@code FORBIDDEN}, {@code NOT_FOUND}, {@code BAD_REQUEST},
 * {@code INTERNAL_ERROR}).
 *
 * <p><strong>Shared contract.</strong> This enum is created once, in M2, with every
 * value the parallel M3 (packet builder) and M4 (limits) branches need, so neither
 * branch adds or edits this file. Both only reference it. A value needed later is
 * added in a single place after M3 and M4 have merged. Clients (ng) match on the
 * enum constant names, so values are never renamed.
 */
public enum SockbowlErrorType implements ErrorClassification {

    // --- M3: packet builder -------------------------------------------------

    /** Optimistic-lock failure: the caller's {@code expectedVersion} is stale. */
    CONFLICT,

    /** Structural validation of a packet, tossup, bonus or import payload failed. */
    VALIDATION_FAILED,

    /** An input exceeded a configured length or size limit. */
    PAYLOAD_TOO_LARGE,

    // --- M4: rate limiting, quotas and abuse controls -----------------------

    /** A per-user or per-IP rate limit rejected the request; retry later. */
    RATE_LIMITED,

    /** A per-role usage quota (packets owned, generations per day, ...) is used up. */
    QUOTA_EXCEEDED,

    /** The caller (user or IP) is banned. */
    BANNED,

    /** The limiter backend (Redis) is unavailable and the path fails closed. */
    LIMITER_UNAVAILABLE
}
