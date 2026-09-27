package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.util.Locale;

/**
 * The caller class a limit or quota is sized for (plan m4-limits section 2.1).
 *
 * <p>Resolved from the highest composite realm role, in the order
 * admin &gt; moderator &gt; author &gt; player; an authenticated user with none of
 * these is {@link #PLAYER}, an anonymous caller is {@link #GUEST}, and a backend
 * client-credentials token is {@link #SERVICE}.
 */
public enum Tier {
    GUEST,
    PLAYER,
    AUTHOR,
    MODERATOR,
    ADMIN,
    SERVICE;

    /** The lower-case name used in config keys, e.g. {@code sockbowl.quota.tiers.guest.*}. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** SERVICE and ADMIN skip per-user quotas (never rate limits). */
    public boolean skipsQuotas() {
        return this == SERVICE || this == ADMIN;
    }
}
