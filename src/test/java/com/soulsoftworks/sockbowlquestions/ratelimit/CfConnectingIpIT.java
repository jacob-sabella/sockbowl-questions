package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlquestions.client.dto.QbRandomFilter;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * G-M4-V1-05/D26: over real HTTP (the servlet container's actual valve
 * pipeline, not MockMvc, since {@code RemoteIpValve} only runs there) with
 * {@code server.forward-headers-strategy=native}, {@code internal-proxies}
 * trusting only the loopback address the test connects from, and
 * {@code remote-ip-header=CF-Connecting-IP}: the IP-keyed {@code import-ip}
 * bucket on {@code POST /api/qbreader/import-random} follows
 * {@code CF-Connecting-IP}, and a spoofed {@code X-Forwarded-For} on the same
 * connection is ignored outright (Tomcat's {@code RemoteIpValve} reads only
 * the header it is configured with). {@link QbreaderImportService} is mocked
 * so the request only needs to clear the rate limiter, not real bank data.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=false",
        "sockbowl.quota.enabled=false",
        "sockbowl.ratelimit.enabled=true",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
        "server.tomcat.remoteip.remote-ip-header=CF-Connecting-IP",
        "sockbowl.ratelimit.policies.import.capacity=1",
        "sockbowl.ratelimit.policies.import.refill-period=1h",
        "sockbowl.ratelimit.policies.import-ip.capacity=1",
        "sockbowl.ratelimit.policies.import-ip.refill-period=1h"
})
class CfConnectingIpIT {

    private static final String IMPORT_RANDOM = "/api/qbreader/import-random";

    @Container
    private static final RedisContainer REDIS = com.soulsoftworks.sockbowlquestions.util.TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    QbreaderImportService importService;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> importAs(String cfConnectingIp, String forwardedFor) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + IMPORT_RANDOM))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"tossupCount\":1,\"bonusCount\":0}"));
        if (cfConnectingIp != null) {
            builder.header("CF-Connecting-IP", cfConnectingIp);
        }
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bucketKeyFollowsCfConnectingIpAndIgnoresASpoofedXForwardedFor() throws Exception {
        Packet packet = Packet.builder().id("pkt-cf-it").name("cf-it").build();
        ImportOutcome outcome = new ImportOutcome(packet, List.of("r1"), 1, 0);
        when(importService.importRandomPacket(any(QbRandomFilter.class), anyInt(), anyInt(), any(), any(),
                anyBoolean(), any(), any(), any())).thenReturn(outcome);

        String realClient = "203.0.113.50";
        String spoofedXff = "203.0.113.99";

        // import-ip: capacity 1/h. The one call lands on the CF-Connecting-IP bucket.
        HttpResponse<String> first = importAs(realClient, spoofedXff);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);

        // Same CF-Connecting-IP, a different spoofed X-Forwarded-For: still the same,
        // now-exhausted bucket (both import and import-ip are IP-keyed here, since
        // this caller is anonymous: KeyBy.USER falls back to the client IP too).
        HttpResponse<String> stillLimited = importAs(realClient, "198.51.100.7");
        assertThat(stillLimited.statusCode()).isEqualTo(429);
        assertThat(stillLimited.headers().firstValue(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isPresent();

        // A different CF-Connecting-IP is a fresh bucket, proving the key is the header
        // value and not e.g. the loopback peer address shared by every call.
        HttpResponse<String> freshRealIp = importAs("203.0.113.51", spoofedXff);
        assertThat(freshRealIp.statusCode()).as(freshRealIp.body()).isEqualTo(200);
    }
}
