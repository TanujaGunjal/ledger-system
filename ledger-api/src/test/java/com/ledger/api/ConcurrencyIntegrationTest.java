package com.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.PostingRequest.EntryType;
import com.ledger.api.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency tests to prove the ledger's correctness under load.
 * Proves:
 * 1. No lost updates (SELECT ... FOR UPDATE works).
 * 2. No deadlocks under bidirectional load (ID ordering works).
 * 3. Concurrent identical idempotency keys only post once (UNIQUE constraint works).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIntegrationTest.class);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long accountA;
    private Long accountB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM account_balances");
        jdbcTemplate.execute("DELETE FROM accounts");

        String aNo = "TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String bNo = "TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        accountA = accountRepository.createAccount(aNo, "Alice", "ASSET", "USD").id();
        accountB = accountRepository.createAccount(bNo, "Bob",   "ASSET", "USD").id();

        // Seed Alice with $1000.00
        jdbcTemplate.update("UPDATE account_balances SET balance = 1000.00 WHERE account_id = ?", accountA);
        // Seed Bob with $1000.00 (so both can send to each other without overdrafting)
        jdbcTemplate.update("UPDATE account_balances SET balance = 1000.00 WHERE account_id = ?", accountB);
    }

    @Test
    @DisplayName("Concurrent transfers: 50 threads A->B, no lost updates")
    void concurrentTransfers_noLostUpdates() throws Exception {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startLatch.await(); // wait for all threads to be ready
                try {
                    PostingRequest req = new PostingRequest("Concurrent A->B", List.of(
                        new PostingRequest.PostingEntry(accountA, EntryType.DEBIT, new BigDecimal("1.00"), "USD"),
                        new PostingRequest.PostingEntry(accountB, EntryType.CREDIT, new BigDecimal("1.00"), "USD")
                    ));
                    mockMvc.perform(post("/api/v1/transactions")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated());
                } finally {
                    doneLatch.countDown();
                }
                return null;
            });
        }

        // Submit all tasks
        for (Callable<Void> task : tasks) {
            executor.submit(task);
        }

        // Fire them all at once
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Alice started with 1000, sent 50x $1 = 950.
        // Bob started with 1000, received 50x $1 = 1050.
        BigDecimal balA = jdbcTemplate.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountA);
        BigDecimal balB = jdbcTemplate.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountB);

        assertThat(balA).isEqualByComparingTo("950.00");
        assertThat(balB).isEqualByComparingTo("1050.00");

        Integer txns = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(txns).isEqualTo(50);
    }

    @Test
    @DisplayName("Concurrent Idempotency: 20 threads submitting the EXACT SAME key only post once")
    void concurrentIdempotency_postsOnlyOnce() throws Exception {
        int threads = 20;
        String sharedIdempotencyKey = UUID.randomUUID().toString();
        PostingRequest req = new PostingRequest("Idempotency Race", List.of(
            new PostingRequest.PostingEntry(accountA, EntryType.DEBIT, new BigDecimal("50.00"), "USD"),
            new PostingRequest.PostingEntry(accountB, EntryType.CREDIT, new BigDecimal("50.00"), "USD")
        ));
        String body = objectMapper.writeValueAsString(req);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // Collect the full MvcResult from every thread so we can parse response bodies.
        List<MvcResult> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                            .header("Idempotency-Key", sharedIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                        .andReturn();
                    results.add(result);
                } catch (Exception e) {
                    log.error("Error in thread", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // All 20 requests must have succeeded (201 first post, 200 idempotent replays).
        long successCount = results.stream()
            .map(r -> r.getResponse().getStatus())
            .filter(s -> s == 200 || s == 201)
            .count();
        assertThat(successCount).isEqualTo(20);

        // THE REAL IDEMPOTENCY PROOF: every response body must contain the exact same
        // transactionRef UUID. If the unique-constraint race produced two separate
        // transaction rows (even with the same idempotency key), one thread would
        // return a different UUID — this assertion would catch that.
        List<String> refs = new ArrayList<>();
        for (MvcResult r : results) {
            String ref = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("transactionRef").asText();
            refs.add(ref);
        }
        String firstRef = refs.get(0);
        assertThat(refs).as("All 20 concurrent idempotent replies must carry the same transactionRef")
            .allMatch(ref -> ref.equals(firstRef));

        // Balance moved exactly once: 1000 - 50 = 950.
        BigDecimal balA = jdbcTemplate.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountA);
        assertThat(balA).isEqualByComparingTo("950.00");

        // Exactly one transaction row in the DB.
        Integer txns = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(txns).isEqualTo(1);
    }

    @Test
    @DisplayName("Deadlock prevention: 50 threads A->B and 50 threads B->A run without deadlock")
    void deadlockPrevention_bidirectionalTransfers() throws Exception {
        int pairs = 50;
        int threads = pairs * 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // We will collect any exceptions to assert no deadlocks occurred
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < pairs; i++) {
            // Task: A -> B
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    PostingRequest req = new PostingRequest("A->B", List.of(
                        new PostingRequest.PostingEntry(accountA, EntryType.DEBIT, new BigDecimal("2.00"), "USD"),
                        new PostingRequest.PostingEntry(accountB, EntryType.CREDIT, new BigDecimal("2.00"), "USD")
                    ));
                    mockMvc.perform(post("/api/v1/transactions")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated());
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));

            // Task: B -> A
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    PostingRequest req = new PostingRequest("B->A", List.of(
                        new PostingRequest.PostingEntry(accountB, EntryType.DEBIT, new BigDecimal("1.00"), "USD"),
                        new PostingRequest.PostingEntry(accountA, EntryType.CREDIT, new BigDecimal("1.00"), "USD")
                    ));
                    mockMvc.perform(post("/api/v1/transactions")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated());
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));
        }

        // Fire all threads
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Check if any thread threw an exception (like PSQLException: deadlock detected)
        for (Future<Void> f : futures) {
            f.get(); // Will throw ExecutionException if the thread failed
        }

        // 50 A->B @ $2 = A loses $100, B gains $100
        // 50 B->A @ $1 = B loses $50, A gains $50
        // Net: A loses $50 (final 950), B gains $50 (final 1050)
        BigDecimal balA = jdbcTemplate.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountA);
        BigDecimal balB = jdbcTemplate.queryForObject("SELECT balance FROM account_balances WHERE account_id = ?", BigDecimal.class, accountB);

        assertThat(balA).isEqualByComparingTo("950.00");
        assertThat(balB).isEqualByComparingTo("1050.00");

        Integer txns = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(txns).isEqualTo(100);
    }
}
