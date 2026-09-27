package com.soulsoftworks.sockbowlquestions.util;

import com.redis.testcontainers.RedisContainer;
import org.testcontainers.utility.DockerImageName;

public class TestcontainersUtil {
    public static RedisContainer getRedisContainer(){
        // Match the redis:8.2 image compose ships (Redis Open Source 8 bundles
        // Search/JSON natively, so the legacy redislabs/redisearch image is no
        // longer needed).
        return new RedisContainer(DockerImageName.parse("redis:8.2")).withExposedPorts(6379);
    }
}
