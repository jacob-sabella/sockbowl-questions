package com.soulsoftworks.sockbowlquestions.ratelimit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The single source of every Redis key the limits, usage and quota code reads
 * or writes (plan m4-limits section 2.1). sockbowl-game and sockbowl-questions
 * share one Redis DB, and this class has <b>identical contents</b> in both
 * repos; {@code UsageKeysContractTest} pins the strings in each, so drift in
 * either one fails its build.
 */
public final class UsageKeys {

    public static final String RL_PREFIX = "rl";

    // Metric names (quota and usage counters).
    public static final String HOSTED_SESSIONS = "hosted-sessions";
    public static final String AI_GENERATIONS = "ai.generations";
    public static final String AI_QUESTIONS = "ai.questions";
    public static final String AI_TOKENS = "ai.tokens";
    public static final String IMPORTS = "imports";
    public static final String PACKETS_OWNED = "packets-owned";
    public static final String AI_SERVERKEY = "ai.serverkey";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private UsageKeys() {
    }

    /** Subject part for an authenticated user: {@code u:{sub}}. */
    public static String userPart(String sub) {
        return "u:" + sub;
    }

    /** Subject part for a client address: {@code ip:{addr}}. */
    public static String ipPart(String ip) {
        return "ip:" + ip;
    }

    /** Subject part for one STOMP connection (local buckets only): {@code conn:{id}}. */
    public static String connectionPart(String connectionId) {
        return "conn:" + connectionId;
    }

    /** Token bucket: {@code rl:{policy}:u:{sub}} or {@code rl:{policy}:ip:{addr}}. */
    public static String rateLimit(String policy, String subjectPart) {
        return rateLimit(RL_PREFIX, policy, subjectPart);
    }

    public static String rateLimit(String prefix, String policy, String subjectPart) {
        return prefix + ":" + policy + ":" + subjectPart;
    }

    /** Rejection event stream. */
    public static String events() {
        return "rl:events";
    }

    /** Sampling guard for {@link #events()}: {@code rl:evsample:{policy}:{key}}. */
    public static String eventSample(String policy, String subjectPart) {
        return "rl:evsample:" + policy + ":" + subjectPart;
    }

    /** UTC day stamp used by the daily counters. */
    public static String day(LocalDate utcDate) {
        return DAY.format(utcDate);
    }

    public static String day(Instant instant) {
        return day(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    /** Daily per-user counter: {@code usage:{sub}:{metric}:d:{yyyyMMdd}}. */
    public static String daily(String sub, String metric, LocalDate utcDate) {
        return "usage:" + sub + ":" + metric + ":d:" + day(utcDate);
    }

    /** Daily global counter: {@code usage:global:{metric}:d:{yyyyMMdd}}. */
    public static String globalDaily(String metric, LocalDate utcDate) {
        return "usage:global:" + metric + ":d:" + day(utcDate);
    }

    /** Hosted-session owner for a signed-in user: {@code u:{sub}}. */
    public static String sessionOwnerUser(String sub) {
        return userPart(sub);
    }

    /** Hosted-session owner for a guest: {@code ip:{addr}}. */
    public static String sessionOwnerIp(String ip) {
        return ipPart(ip);
    }

    /** Hosted sessions ZSET (sessionId to last activity epoch ms): {@code usage:{owner}:sessions}. */
    public static String sessions(String owner) {
        return "usage:" + owner + ":sessions";
    }

    /** Per-user meta hash {@code {tier, lastSeenAt, username}}: {@code usage:{sub}:meta}. */
    public static String meta(String sub) {
        return "usage:" + sub + ":meta";
    }

    /** Last-seen IPs ZSET (ip to epoch ms): {@code usage:{sub}:ips}. */
    public static String ips(String sub) {
        return "usage:" + sub + ":ips";
    }

    /** Per-user quota overrides hash (metric to limit, -1 = unlimited): {@code quota:override:{sub}}. */
    public static String quotaOverride(String sub) {
        return "quota:override:" + sub;
    }

    /** Subject ban mirror, JSON {@code {reason, expiresAt}}: {@code ban:{sub}}. */
    public static String ban(String sub) {
        return "ban:" + sub;
    }

    /** IP ban mirror hash (banId to {@code {cidr, expiresAtEpochMs}}). */
    public static String ipBans() {
        return "ipban:all";
    }

    /** AI generation concurrency lock: {@code ai:inflight:{sub}}. */
    public static String aiInflight(String sub) {
        return "ai:inflight:" + sub;
    }
}
