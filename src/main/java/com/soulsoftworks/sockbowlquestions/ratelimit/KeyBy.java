package com.soulsoftworks.sockbowlquestions.ratelimit;

/** What a policy's bucket is keyed by. */
public enum KeyBy {
    /** The user's {@code sub} when authenticated, otherwise the client IP. */
    USER_OR_IP,
    /** Always the client IP. */
    IP,
    /** The user's {@code sub} (an anonymous caller falls back to its IP). */
    USER,
    /** A single STOMP connection; in-memory only ({@link LocalBucketRegistry}). */
    CONNECTION
}
