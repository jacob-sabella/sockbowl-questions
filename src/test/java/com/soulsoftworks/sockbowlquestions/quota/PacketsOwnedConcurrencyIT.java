package com.soulsoftworks.sockbowlquestions.quota;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlquestions.ai.ScriptedChatModel;
import com.soulsoftworks.sockbowlquestions.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlquestions.service.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Q-V1-01 acceptance: before the fix, {@link ContentQuotaGuard#checkPacketsOwned}
 * alone was a plain read-then-create, so concurrent callers at a
 * {@code packets-owned} limit could all read the count below the limit and all
 * then create, overshooting it. {@link ContentQuotaGuard#withPacketsOwnedSlot}
 * closes that race by serializing one owner's check-and-create behind a
 * per-owner Redis lock.
 *
 * <p>Every one of the three entry points that create an owned packet is
 * exercised: {@code createPacket} (GraphQL), an owned {@code import-random},
 * and {@code POST /api/packets/generate}. In each case, an author with a
 * {@code packets-owned} override of 2 fires {@value #CONCURRENCY} concurrent
 * creates at once; exactly 2 must succeed, the other
 * {@value #CONCURRENCY} - 2 must be {@code QUOTA_EXCEEDED} (each reporting
 * {@code used == 2}, never an overshoot), and the owner must end up owning
 * exactly 2 packets.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(ContentQuotaITSupport.ClockConfig.class)
class PacketsOwnedConcurrencyIT extends ContentQuotaITSupport {

    private static final int CONCURRENCY = 24;
    private static final long OVERRIDE = 2;

    /** Never actually calls an AI provider (WP-Q3's usual double); shared across the class. */
    private static final ScriptedChatModel CHAT = new ScriptedChatModel();

    @MockitoBean
    ChatClientFactory chatClientFactory;

    @BeforeEach
    void wireAi() {
        CHAT.reset();
        when(chatClientFactory.getChatClient(any())).thenAnswer(inv -> ChatClient.builder(CHAT).build());
    }

    @Test
    void createPacketConcurrencyNeverOvershootsTheOwnedQuota() throws Exception {
        String sub = nextSub("cc-create");
        setOverride(sub, UsageKeys.PACKETS_OWNED, OVERRIDE);

        List<JsonObject> responses = runConcurrently(CONCURRENCY,
                i -> createPacket(author(sub), PREFIX + "cc-create-" + i));

        int ok = 0;
        int exceeded = 0;
        for (JsonObject response : responses) {
            if (response.has("errors")) {
                assertGraphQlQuotaExceeded(response, UsageKeys.PACKETS_OWNED, OVERRIDE, OVERRIDE);
                exceeded++;
            } else {
                ok++;
            }
        }
        assertThat(ok).as("successful creates").isEqualTo((int) OVERRIDE);
        assertThat(exceeded).as("quota-exceeded creates").isEqualTo(CONCURRENCY - (int) OVERRIDE);
        assertThat(ownedPackets(sub)).isEqualTo(OVERRIDE);
    }

    @Test
    void ownedImportRandomConcurrencyNeverOvershootsTheOwnedQuota() throws Exception {
        String sub = nextSub("cc-import");
        setOverride(sub, UsageKeys.PACKETS_OWNED, OVERRIDE);

        List<MvcResult> results = runConcurrently(CONCURRENCY, i -> importRandom(author(sub)));

        assertOwnedRaceIsClosed(results, sub);
    }

    @Test
    void generateConcurrencyNeverOvershootsTheOwnedQuota() throws Exception {
        String sub = nextSub("cc-gen");
        setOverride(sub, UsageKeys.PACKETS_OWNED, OVERRIDE);

        List<MvcResult> results = runConcurrently(CONCURRENCY, i -> generate(author(sub)));

        assertOwnedRaceIsClosed(results, sub);
    }

    /* ------------------------------------------------------------------ */

    private MvcResult generate(RequestPostProcessor caller) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("topic", "q-v1-01 quota race");
        body.addProperty("questionCount", 1);
        body.addProperty("generateBonuses", false);
        return mvc.perform(post("/api/packets/generate").with(caller)
                .header("X-API-Key", "sk-cc-byo").header("X-Model", "byo-model")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    private void assertOwnedRaceIsClosed(List<MvcResult> results, String sub) throws Exception {
        int ok = 0;
        int exceeded = 0;
        for (MvcResult result : results) {
            int status = result.getResponse().getStatus();
            if (status == 429) {
                assertQuotaExceeded(result, UsageKeys.PACKETS_OWNED, OVERRIDE, OVERRIDE, null);
                exceeded++;
            } else {
                assertThat(status).as(result.getResponse().getContentAsString()).isEqualTo(200);
                ok++;
            }
        }
        assertThat(ok).as("successful creates").isEqualTo((int) OVERRIDE);
        assertThat(exceeded).as("quota-exceeded creates").isEqualTo(CONCURRENCY - (int) OVERRIDE);
        assertThat(ownedPackets(sub)).isEqualTo(OVERRIDE);
    }

    /** Fires {@code n} calls at once (a start barrier, not just a submit loop) and returns their results in order. */
    private <T> List<T> runConcurrently(int n, ThrowingIntFunction<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<T> task = () -> {
                    ready.countDown();
                    go.await();
                    return call.apply(idx);
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("every caller reached the start barrier").isTrue();
            go.countDown();

            List<T> results = new ArrayList<>(n);
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntFunction<T> {
        T apply(int i) throws Exception;
    }
}
