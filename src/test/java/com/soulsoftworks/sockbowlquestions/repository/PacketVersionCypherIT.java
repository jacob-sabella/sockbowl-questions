package com.soulsoftworks.sockbowlquestions.repository;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PacketRepository#bumpVersion} and {@link PacketRepository#currentVersion} against a
 * real Neo4j (M3 Q2, PB-18, plan 3.1.4): the null → 1 → 2 sequence, a stale
 * {@code expected} returning null without writing, and write serialization between
 * concurrent transactions (the lock is taken before the version is read).
 */
@SpringBootTest
class PacketVersionCypherIT extends Neo4jContainerTestBase {

    private static final String PREFIX = "pkt-ver-";

    @Autowired private PacketRepository repository;
    @Autowired private Neo4jClient neo4j;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        clean();
        tx = new TransactionTemplate(transactionManager);
        pool = Executors.newFixedThreadPool(8);
        // A legacy node: no version property at all.
        neo4j.query("CREATE (:Packet {id: 'pkt-ver-a', name: 'pkt-ver A', visibility: 'DRAFT'})").run();
    }

    @AfterEach
    void clean() {
        if (pool != null) {
            pool.shutdownNow();
        }
        neo4j.query("MATCH (p:Packet) WHERE p.id STARTS WITH $prefix DETACH DELETE p")
                .bind(PREFIX).to("prefix").run();
    }

    private Long storedVersion(String id) {
        return neo4j.query("MATCH (p:Packet {id: $id}) RETURN p.version AS v")
                .bind(id).to("id").fetchAs(Long.class).one().orElse(null);
    }

    @Test
    void bumpIncrementsFromNullToOneToTwo() {
        assertThat(storedVersion("pkt-ver-a")).isNull();
        assertThat(repository.currentVersion("pkt-ver-a")).contains(0L);

        assertThat(repository.bumpVersion("pkt-ver-a", null)).isEqualTo(1L);
        assertThat(repository.bumpVersion("pkt-ver-a", 1L)).isEqualTo(2L);

        assertThat(storedVersion("pkt-ver-a")).isEqualTo(2L);
        assertThat(repository.currentVersion("pkt-ver-a")).contains(2L);
    }

    @Test
    void expectedZeroMatchesALegacyNodeWithoutVersion() {
        assertThat(repository.bumpVersion("pkt-ver-a", 0L)).isEqualTo(1L);
    }

    @Test
    void mismatchReturnsNullAndWritesNothing() {
        repository.bumpVersion("pkt-ver-a", null);

        assertThat(repository.bumpVersion("pkt-ver-a", 0L)).isNull();
        assertThat(repository.bumpVersion("pkt-ver-a", 7L)).isNull();

        assertThat(storedVersion("pkt-ver-a")).isEqualTo(1L);
        Long lockLeftovers = neo4j.query("MATCH (p:Packet {id: 'pkt-ver-a'}) RETURN count(p.versionLock)")
                .fetchAs(Long.class).one().orElseThrow();
        assertThat(lockLeftovers).isZero();
    }

    @Test
    void missingPacketReturnsNullAndHasNoCurrentVersion() {
        assertThat(repository.bumpVersion("pkt-ver-missing", null)).isNull();
        assertThat(repository.currentVersion("pkt-ver-missing")).isEmpty();
    }

    @Test
    void secondTransactionWaitsForTheFirstAndThenSeesItsVersion() throws Exception {
        CountDownLatch firstBumped = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<Long> first = pool.submit(() -> tx.execute(status -> {
            Long v = repository.bumpVersion("pkt-ver-a", 0L);
            firstBumped.countDown();
            await(releaseFirst);
            return v;
        }));
        assertThat(firstBumped.await(30, TimeUnit.SECONDS)).isTrue();

        Future<Long> second = pool.submit(() -> tx.execute(status -> repository.bumpVersion("pkt-ver-a", 0L)));

        // While the first transaction holds the packet's write lock, the second one blocks.
        assertThatThrownBy(() -> second.get(750, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1L);
        // After the first commits, the second re-reads version 1 under the lock: stale.
        assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
        assertThat(storedVersion("pkt-ver-a")).isEqualTo(1L);
    }

    @Test
    void concurrentBumpsWithTheSameExpectedVersion_exactlyOneSucceeds() throws Exception {
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return tx.execute(status -> repository.bumpVersion("pkt-ver-a", 0L));
            }));
        }
        List<Long> outcomes = new ArrayList<>();
        for (Future<Long> f : results) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }

        assertThat(outcomes.stream().filter(Objects::nonNull).toList()).containsExactly(1L);
        assertThat(storedVersion("pkt-ver-a")).isEqualTo(1L);
    }

    @Test
    void concurrentUncheckedBumpsLoseNoUpdates() throws Exception {
        int threads = 8;
        int perThread = 5;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                for (int j = 0; j < perThread; j++) {
                    tx.execute(status -> repository.bumpVersion("pkt-ver-a", null));
                }
                return null;
            }));
        }
        for (Future<?> f : results) {
            f.get(60, TimeUnit.SECONDS);
        }

        assertThat(storedVersion("pkt-ver-a")).isEqualTo((long) threads * perThread);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test to release the lock holder");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
