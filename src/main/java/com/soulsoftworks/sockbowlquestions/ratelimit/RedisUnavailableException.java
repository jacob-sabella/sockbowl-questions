package com.soulsoftworks.sockbowlquestions.ratelimit;

/** The limiter's Redis connection is down (or still backing off after a failed connect). */
public class RedisUnavailableException extends RuntimeException {
    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
