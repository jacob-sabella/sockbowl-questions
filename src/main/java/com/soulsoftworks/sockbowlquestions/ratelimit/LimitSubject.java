package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.util.Objects;

/**
 * Who a limit is charged to (plan m4-limits section 2.1).
 *
 * @param sub  the Keycloak subject, or {@code null} for an anonymous caller
 * @param ip   the client address, already normalized by
 *             {@link ClientIpResolver#normalize(String)} (IPv6 truncated to its /64)
 * @param tier the caller class
 */
public record LimitSubject(String sub, String ip, Tier tier) {

    public LimitSubject {
        Objects.requireNonNull(tier, "tier");
        if (sub != null && sub.isBlank()) {
            sub = null;
        }
    }

    public static LimitSubject guest(String ip) {
        return new LimitSubject(null, ip, Tier.GUEST);
    }

    public boolean isAuthenticated() {
        return sub != null;
    }

    /**
     * The subject part of a bucket key for the given key strategy:
     * {@code u:{sub}} or {@code ip:{addr}}. {@link KeyBy#USER} falls back to the
     * IP for an anonymous caller so a guest can never share one global bucket.
     */
    public String keyFor(KeyBy keyBy) {
        return switch (keyBy) {
            case IP -> UsageKeys.ipPart(ip);
            case USER, USER_OR_IP -> sub != null ? UsageKeys.userPart(sub) : UsageKeys.ipPart(ip);
            case CONNECTION -> throw new IllegalArgumentException(
                    "CONNECTION-keyed policies are local; use LocalBucketRegistry");
        };
    }
}
