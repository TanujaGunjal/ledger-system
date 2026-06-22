package com.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.PostingRequest.EntryType;
import com.ledger.api.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for POST /api/v1/transactions.
 *
 * Requires `docker-compose up -d` (Postgres + Redis + Redpanda) to be running
 * before executing `mvn test`. Each test cleans up the rows it creates via
 * @BeforeEach so tests are independent of each other.
 *
 * What each test proves:
 *   (a) A single transfer correctly updates both account balances.
 *   (b) Sending the same Idempotency-Key twice posts only one transaction.
 *   (c) A request where debits and credits don't net to zero is rejected (400).
 *   (d) A DEBIT that would overdraw is rejected (409) and no partial state persists.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostTransactionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long debitAccountId;
    private Long creditAccountId;

    /**
     * Create fresh accounts for each test and wipe any transactions/entries
     * left from previous runs. Using explicit teardown rather than
     * @Transactional rollback because MockMvc tests run in a different thread
     * from the @Transactional test method — the rollback wouldn't see the rows.
     */
    @BeforeEach
    void setUp() {
        // Tear down in FK order — also clearing outbox_events so Phase 2 rows
        // don't cause cross-test interference once the outbox is wired in.
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM account_balances");
        jdbcTemplate.execute("DELETE FROM accounts");

        // Create two test accounts with a seeded starting balance for the debit account.
        // Account numbers must fit VARCHAR(34): use 8 hex chars + short prefix = 10 chars total.
        String debitAccNo  = "TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String creditAccNo = "TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        debitAccountId  = accountRepository.createAccount(debitAccNo,  "Alice", "ASSET", "USD").id();
        creditAccountId = accountRepository.createAccount(creditAccNo, "Bob",   "ASSET", "USD").id();

        // Seed Alice with $1000 so transfer tests have funds to work with.
        jdbcTemplate.update(
            "UPDATE account_balances SET balance = 1000.00 WHERE account_id = ?",
            debitAccountId
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (a) Happy path: single transfer updates both balances correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) Single transfer: both account balances updated correctly")
    void singleTransfer_updatesBothBalances() throws Exception {
        PostingRequest request = new PostingRequest(
            "Test transfer Alice to Bob",
            List.of(
                new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("200.00"), "USD"),
                new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("200.00"), "USD")
            )
        );

        mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.entries").isArray())
            .andExpect(jsonPath("$.entries.length()").value(2));

        // Verify the materialized balances directly.
        BigDecimal aliceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class, debitAccountId);
        BigDecimal bobBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class, creditAccountId);

        assertThat(aliceBalance).isEqualByComparingTo("800.00");
        assertThat(bobBalance).isEqualByComparingTo("200.00");

        // Verify two ledger_entries rows were written.
        Integer entryCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries", Integer.class);
        assertThat(entryCount).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (b) Idempotency: same key twice posts only once
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b) Duplicate Idempotency-Key: only one transaction posted")
    void duplicateIdempotencyKey_postsOnlyOnce() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        PostingRequest request = new PostingRequest(
            "Idempotency test",
            List.of(
                new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("50.00"), "USD"),
                new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("50.00"), "USD")
            )
        );
        String body = objectMapper.writeValueAsString(request);

        // First submission — should create the transaction.
        MvcResult first = mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        // Second submission with the same key — must return the same result, not duplicate.
        MvcResult second = mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn();

        // Both responses must reference the same transactionRef.
        String firstRef  = objectMapper.readTree(first.getResponse().getContentAsString()).get("transactionRef").asText();
        String secondRef = objectMapper.readTree(second.getResponse().getContentAsString()).get("transactionRef").asText();
        assertThat(firstRef).isEqualTo(secondRef);

        // Exactly one transactions row and two ledger_entries rows in the DB.
        Integer txnCount   = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions",   Integer.class);
        Integer entryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
        assertThat(txnCount).isEqualTo(1);
        assertThat(entryCount).isEqualTo(2);

        // Balance moved exactly once: 1000 - 50 = 950.
        BigDecimal aliceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class, debitAccountId);
        assertThat(aliceBalance).isEqualByComparingTo("950.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (c) Unbalanced posting rejected with 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) Unbalanced posting: debits != credits → 400, nothing written")
    void unbalancedPosting_rejected400() throws Exception {
        PostingRequest request = new PostingRequest(
            "Unbalanced — debit $100, credit $90",
            List.of(
                new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("100.00"), "USD"),
                new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("90.00"),  "USD")
            )
        );

        mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Unbalanced Posting"));

        // Nothing written to the database.
        Integer txnCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        assertThat(txnCount).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (d) Overdraft rejected with 409, no partial state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) Overdraft: DEBIT > balance → 409, no partial state persisted")
    void overdraft_rejected409_noPartialState() throws Exception {
        // Alice has $1000; try to debit $1500 — should fail.
        PostingRequest request = new PostingRequest(
            "Overdraft attempt",
            List.of(
                new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("1500.00"), "USD"),
                new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("1500.00"), "USD")
            )
        );

        mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Insufficient Funds"));

        // No transactions row, no ledger entries, balances unchanged.
        Integer txnCount   = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions",   Integer.class);
        Integer entryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
        assertThat(txnCount).isZero();
        assertThat(entryCount).isZero();

        // Alice's balance must still be 1000.
        BigDecimal aliceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class, debitAccountId);
        assertThat(aliceBalance).isEqualByComparingTo("1000.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (e) Missing Idempotency-Key header → 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing Idempotency-Key header → 400")
    void missingIdempotencyKey_rejected400() throws Exception {
        PostingRequest request = new PostingRequest(
            "No idempotency key",
            List.of(
                new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("10.00"), "USD"),
                new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("10.00"), "USD")
            )
        );

        mockMvc.perform(post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Missing Idempotency-Key Header"));
    }
}
