package com.soulsoftworks.sockbowlquestions.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The limiter's own Lettuce connections to the shared Redis (plan m4-limits
 * section 2.2), separate from Redis OM's Jedis factory.
 *
 * <p>Connecting is <b>lazy</b>: nothing is opened at startup, so the service
 * boots (and every limiter fails open, D12) while Redis is down. A failed
 * connect is not retried for {@code sockbowl.ratelimit.redis.reconnect-backoff},
 * so a Redis outage costs one fast failure per request rather than a connect
 * attempt each. Once connected, Lettuce reconnects on its own, and commands
 * issued while it is disconnected are rejected immediately instead of queued.
 */
@Slf4j
public class RateLimitRedis implements DisposableBean {

    private final RedisClient client;
    private final RateLimitTimeMeter timeMeter;
    private final Duration commandTimeout;
    private final long reconnectBackoffNanos;

    private volatile Connections connections;
    private volatile long nextAttemptNanos;

    private record Connections(StatefulRedisConnection<String, byte[]> bytes,
                               StatefulRedisConnection<String, String> strings,
                               ProxyManager<String> proxyManager) {
    }

    public RateLimitRedis(String host, int port, int database, String password,
                          RateLimitProperties.Redis settings, RateLimitTimeMeter timeMeter) {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withTimeout(settings.getTimeout());
        if (password != null && !password.isBlank()) {
            uri.withPassword(password.toCharArray());
        }
        this.client = RedisClient.create(uri.build());
        this.client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(settings.getTimeout()))
                .socketOptions(SocketOptions.builder().connectTimeout(settings.getConnectTimeout()).build())
                .build());
        this.timeMeter = timeMeter;
        this.commandTimeout = settings.getTimeout();
        this.reconnectBackoffNanos = settings.getReconnectBackoff().toNanos();
    }

    /** bucket4j proxy manager over the byte-valued connection. */
    public ProxyManager<String> proxyManager() {
        return connections().proxyManager();
    }

    /** Synchronous string commands (quota Lua, override lookups, ...). */
    public RedisCommands<String, String> sync() {
        return connections().strings().sync();
    }

    /** Asynchronous string commands, for fire-and-forget writes. */
    public RedisAsyncCommands<String, String> async() {
        return connections().strings().async();
    }

    public Duration commandTimeout() {
        return commandTimeout;
    }

    private Connections connections() {
        Connections current = connections;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (connections != null) {
                return connections;
            }
            long now = System.nanoTime();
            if (nextAttemptNanos != 0 && now - nextAttemptNanos < 0) {
                throw new RedisUnavailableException("limiter Redis unavailable (backing off)", null);
            }
            try {
                StatefulRedisConnection<String, byte[]> bytes =
                        client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                StatefulRedisConnection<String, String> strings;
                try {
                    strings = client.connect(StringCodec.UTF8);
                } catch (RuntimeException e) {
                    bytes.closeAsync();
                    throw e;
                }
                ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(bytes)
                        .clientClock(timeMeter)
                        .requestTimeout(commandTimeout)
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ZERO))
                        .build();
                connections = new Connections(bytes, strings, proxyManager);
                nextAttemptNanos = 0;
                return connections;
            } catch (RuntimeException e) {
                nextAttemptNanos = now + reconnectBackoffNanos;
                throw new RedisUnavailableException("limiter Redis connect failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void destroy() {
        Connections current = connections;
        if (current != null) {
            current.bytes().close();
            current.strings().close();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }

    /** Backoff remaining, for diagnostics/tests. */
    long backoffRemainingMillis() {
        long remaining = nextAttemptNanos - System.nanoTime();
        return nextAttemptNanos == 0 || remaining < 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(remaining);
    }
}
