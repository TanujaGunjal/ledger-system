package com.ledger.reconciliation;

import com.ledger.reconciliation.domain.ReconciliationException;
import com.ledger.reconciliation.domain.ReconciliationRunResult;
import com.ledger.reconciliation.repository.ReconciliationExceptionRepository;
import com.ledger.reconciliation.repository.ReconciliationMatchRepository;
import com.ledger.reconciliation.service.ReconciliationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ReconciliationEngine.
 *
 * Design choices:
 * - reconcileOnce() is called DIRECTLY (not via the scheduler) so tests are
 *   deterministic — no timing dependency on the 60-second scheduler tick.
 * - MockFeedGenerator and ReconciliationScheduler beans do NOT exist in the
 *   test context (reconciliation.scheduling.enabled=false in test application.yml).
 * - Flyway runs V1 + V2 against the docker-compose Postgres in test application.yml,
 *   so these tests do not require ledger-api to have started first.
 * - grace-period-hours=0 so transactions posted moments ago are eligible for
 *   MISSING_EXTERNAL without waiting 24 hours.
 *
 * Tests:
 *   1. cleanMatch              — exact amount/date match → match row, no exception
 *   2. amountMismatch          — |delta| > tolerance    → AMOUNT_MISMATCH exception
 *   3. missingExternal         — no external entry      → MISSING_EXTERNAL exception
 *   4. missingInternal         — orphan external entry  → MISSING_INTERNAL exception
 *   5. selfResolution          — MISSING_EXTERNAL on run 1, external arrives, RESOLVED + match on run 2
 */
@SpringBootTest
class ReconciliationEngineIntegrationTest {

    @Autowired ReconciliationEngine                engine;
    @Autowired ReconciliationExceptionRepository   exceptionRepo;
    @Autowired ReconciliationMatchRepository       matchRepo;
    @Autowired JdbcTemplate                        jdbc;

    // Shared account ids created once per test — FK required by ledger_entries
    private long debitAccountId;
    private long creditAccountId;

    @BeforeEach
    void setUp() {
        // Wipe reconciliation tables first (no FKs back to core tables from these)
        jdbc.execute("DELETE FROM reconciliation_matches");
        jdbc.execute("DELETE FROM reconciliation_exceptions");
        jdbc.execute("DELETE FROM external_statement_entries");
        // Wipe core ledger tables in FK order
        jdbc.execute("DELETE FROM ledger_entries");
        jdbc.execute("DELETE FROM outbox_events");
        jdbc.execute("DELETE FROM transactions");
        jdbc.execute("DELETE FROM account_balances");
        jdbc.execute("DELETE FROM accounts");

        // Create two accounts for all test transactions to reference
        debitAccountId  = insertAccount("RECON-DEBIT-"  + shortId());
        creditAccountId = insertAccount("RECON-CREDIT-" + shortId());
    }

    // ── Test 1: clean exact match ─────────────────────────────────────────────

    @Test
    @DisplayName("Clean match: exact amount and date → reconciliation_matches row, no exception")
    void cleanMatch_producesMatchRowAndNoException() {
        String ref = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("100.00");

        insertPostedTransaction(ref, amount, "USD");
        insertExternalEntry(ref, amount, "USD", LocalDate.now());

        ReconciliationRunResult result = engine.reconcileOnce();

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.missingExternal()).isZero();
        assertThat(result.missingInternal()).isZero();
        assertThat(result.amountMismatch()).isZero();
        assertThat(result.resolved()).isZero();

        // Verify the match row exists in the DB
        Integer matchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_matches WHERE transaction_ref = ?",
                Integer.class, ref);
        assertThat(matchCount).isEqualTo(1);

        // Verify no exception was raised
        List<ReconciliationException> exceptions = exceptionRepo.findByTransactionRef(ref);
        assertThat(exceptions).isEmpty();
    }

    // ── Test 2: amount mismatch ───────────────────────────────────────────────

    @Test
    @DisplayName("Amount mismatch: |delta| > tolerance (0.05) → AMOUNT_MISMATCH exception")
    void amountMismatch_producesAmountMismatchException() {
        String ref = UUID.randomUUID().toString();
        BigDecimal internalAmount = new BigDecimal("50.00");
        BigDecimal externalAmount = new BigDecimal("50.10");  // delta = 0.10 > tolerance 0.05

        insertPostedTransaction(ref, internalAmount, "USD");
        insertExternalEntry(ref, externalAmount, "USD", LocalDate.now());

        ReconciliationRunResult result = engine.reconcileOnce();

        assertThat(result.amountMismatch()).isEqualTo(1);
        assertThat(result.matched()).isZero();
        assertThat(result.missingExternal()).isZero();

        List<ReconciliationException> exceptions = exceptionRepo.findByTransactionRef(ref);
        assertThat(exceptions).hasSize(1);

        ReconciliationException ex = exceptions.get(0);
        assertThat(ex.exceptionType()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(ex.status()).isEqualTo("OPEN");
        assertThat(ex.internalAmount()).isEqualByComparingTo(internalAmount);
        assertThat(ex.externalAmount()).isEqualByComparingTo(externalAmount);
        assertThat(ex.resolvedAt()).isNull();
    }

    @Test
    @DisplayName("Amount mismatch within tolerance (0.03 <= 0.05) → clean match, no exception")
    void amountWithinTolerance_countsAsCleanMatch() {
        String ref = UUID.randomUUID().toString();
        BigDecimal internalAmount = new BigDecimal("75.00");
        BigDecimal externalAmount = new BigDecimal("75.03");  // delta = 0.03 <= tolerance 0.05

        insertPostedTransaction(ref, internalAmount, "USD");
        insertExternalEntry(ref, externalAmount, "USD", LocalDate.now());

        ReconciliationRunResult result = engine.reconcileOnce();

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.amountMismatch()).isZero();
        assertThat(exceptionRepo.findByTransactionRef(ref)).isEmpty();
    }

    // ── Test 3: missing external ──────────────────────────────────────────────

    @Test
    @DisplayName("No external entry after grace period → MISSING_EXTERNAL exception")
    void missingExternal_producesException() {
        String ref = UUID.randomUUID().toString();
        insertPostedTransaction(ref, new BigDecimal("25.00"), "USD");
        // Deliberately no external entry

        ReconciliationRunResult result = engine.reconcileOnce();

        assertThat(result.missingExternal()).isEqualTo(1);
        assertThat(result.matched()).isZero();

        List<ReconciliationException> exceptions = exceptionRepo.findByTransactionRef(ref);
        assertThat(exceptions).hasSize(1);

        ReconciliationException ex = exceptions.get(0);
        assertThat(ex.exceptionType()).isEqualTo("MISSING_EXTERNAL");
        assertThat(ex.status()).isEqualTo("OPEN");
        assertThat(ex.internalAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(ex.externalReference()).isNull();
        assertThat(ex.resolvedAt()).isNull();
    }

    // ── Test 4: missing internal ──────────────────────────────────────────────

    @Test
    @DisplayName("Orphan external entry (no internal transaction) → MISSING_INTERNAL exception")
    void missingInternal_producesException() {
        String orphanRef = "ORPHAN-" + UUID.randomUUID().toString().substring(0, 8);
        insertExternalEntry(orphanRef, new BigDecimal("99.99"), "USD", LocalDate.now());
        // Deliberately no matching internal transaction

        ReconciliationRunResult result = engine.reconcileOnce();

        assertThat(result.missingInternal()).isEqualTo(1);
        assertThat(result.matched()).isZero();

        // Look up by external_reference
        List<ReconciliationException> exceptions = jdbc.query(
                """
                SELECT id, exception_type, status, transaction_ref, external_reference,
                       internal_amount, external_amount, details::text,
                       created_at, resolved_at
                FROM   reconciliation_exceptions
                WHERE  external_reference = ?
                """,
                ps -> ps.setString(1, orphanRef),
                (rs, i) -> new ReconciliationException(
                        rs.getLong("id"),
                        rs.getString("exception_type"),
                        rs.getString("status"),
                        rs.getString("transaction_ref"),
                        rs.getString("external_reference"),
                        rs.getBigDecimal("internal_amount"),
                        rs.getBigDecimal("external_amount"),
                        rs.getString("details"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at") != null
                                ? rs.getTimestamp("resolved_at").toInstant() : null
                ));

        assertThat(exceptions).hasSize(1);
        ReconciliationException ex = exceptions.get(0);
        assertThat(ex.exceptionType()).isEqualTo("MISSING_INTERNAL");
        assertThat(ex.status()).isEqualTo("OPEN");
        assertThat(ex.transactionRef()).isNull();
        assertThat(ex.externalAmount()).isEqualByComparingTo(new BigDecimal("99.99"));
    }

    // ── Test 5: self-resolution ───────────────────────────────────────────────

    @Test
    @DisplayName("Self-resolution: MISSING_EXTERNAL on run 1, external arrives, RESOLVED + match on run 2")
    void selfResolution_missingExternalThenExternalArrives_exceptionResolvedAndMatchCreated() {
        String ref = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("200.00");

        // ── Run 1: no external entry yet ──────────────────────────────────────
        insertPostedTransaction(ref, amount, "USD");

        ReconciliationRunResult run1 = engine.reconcileOnce();

        assertThat(run1.missingExternal()).isEqualTo(1);
        assertThat(run1.matched()).isZero();
        assertThat(run1.resolved()).isZero();

        List<ReconciliationException> afterRun1 = exceptionRepo.findByTransactionRef(ref);
        assertThat(afterRun1).hasSize(1);
        assertThat(afterRun1.get(0).exceptionType()).isEqualTo("MISSING_EXTERNAL");
        assertThat(afterRun1.get(0).status()).isEqualTo("OPEN");
        assertThat(afterRun1.get(0).resolvedAt()).isNull();

        Integer matchesAfterRun1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_matches WHERE transaction_ref = ?",
                Integer.class, ref);
        assertThat(matchesAfterRun1).isZero();

        // ── Insert the external entry (simulates late bank statement delivery) ─
        insertExternalEntry(ref, amount, "USD", LocalDate.now());

        // ── Run 2: external entry now exists — expect resolution ───────────────
        ReconciliationRunResult run2 = engine.reconcileOnce();

        assertThat(run2.matched()).isEqualTo(1);
        assertThat(run2.resolved()).isEqualTo(1);   // the MISSING_EXTERNAL was resolved
        assertThat(run2.missingExternal()).isZero();

        // Exception row must now be RESOLVED with a non-null resolved_at
        List<ReconciliationException> afterRun2 = exceptionRepo.findByTransactionRef(ref);
        assertThat(afterRun2).hasSize(1);
        ReconciliationException resolvedEx = afterRun2.get(0);
        assertThat(resolvedEx.exceptionType()).isEqualTo("MISSING_EXTERNAL");
        assertThat(resolvedEx.status()).isEqualTo("RESOLVED");
        assertThat(resolvedEx.resolvedAt()).isNotNull();

        // A reconciliation_matches row must now exist
        Integer matchesAfterRun2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_matches WHERE transaction_ref = ?",
                Integer.class, ref);
        assertThat(matchesAfterRun2).isEqualTo(1);

        // Running the engine a third time should produce no new exceptions or matches
        ReconciliationRunResult run3 = engine.reconcileOnce();
        assertThat(run3.matched()).isZero();
        assertThat(run3.missingExternal()).isZero();
        assertThat(run3.resolved()).isZero();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertAccount(String accountNumber) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO accounts (account_number, owner_name, account_type, currency) " +
                    "VALUES (?, 'Test Owner', 'ASSET', 'USD')",
                    new String[]{"id"});
            ps.setString(1, accountNumber);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        // account_balances is required by the FK on account_id
        jdbc.update("INSERT INTO account_balances (account_id, balance) VALUES (?, 1000.00)", id);
        return id;
    }

    /**
     * Insert a POSTED transaction with a single DEBIT ledger entry.
     * posted_at is set to 2 hours ago so it's always past any grace period
     * (tests use grace-period-hours=0, but the offset also covers future changes).
     */
    private void insertPostedTransaction(String transactionRef, BigDecimal amount, String currency) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions " +
                    "    (transaction_ref, idempotency_key, description, status, created_at, posted_at) " +
                    "VALUES (?::uuid, ?, 'Reconciliation test', 'POSTED', now(), now() - INTERVAL '2 hours')",
                    new String[]{"id"});
            ps.setString(1, transactionRef);
            ps.setString(2, UUID.randomUUID().toString());
            return ps;
        }, kh);
        long txnId = kh.getKey().longValue();

        // DEBIT entry — the engine sums these for the internal amount
        jdbc.update(
                "INSERT INTO ledger_entries " +
                "    (transaction_id, account_id, entry_type, amount, currency, sequence_no) " +
                "VALUES (?, ?, 'DEBIT', ?, ?, 1)",
                txnId, debitAccountId, amount, currency);
    }

    private void insertExternalEntry(String externalReference, BigDecimal amount,
                                     String currency, LocalDate statementDate) {
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO external_statement_entries " +
                    "    (external_reference, amount, currency, statement_date) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (external_reference) DO NOTHING");
            ps.setString(1, externalReference);
            ps.setBigDecimal(2, amount);
            ps.setString(3, currency);
            ps.setDate(4, Date.valueOf(statementDate));
            return ps;
        });
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
