package com.ledger.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.reconciliation.domain.ExceptionType;
import com.ledger.reconciliation.domain.ExternalStatementEntry;
import com.ledger.reconciliation.domain.ReconciliationRunResult;
import com.ledger.reconciliation.domain.UnmatchedTransaction;
import com.ledger.reconciliation.repository.ExternalStatementRepository;
import com.ledger.reconciliation.repository.InternalLedgerRepository;
import com.ledger.reconciliation.repository.ReconciliationExceptionRepository;
import com.ledger.reconciliation.repository.ReconciliationMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/*
 * ════════════════════════════════════════════════════════════════════════
 * RECONCILIATION ALGORITHM — Phase 3 (approved + self-resolution)
 * ════════════════════════════════════════════════════════════════════════
 *
 * MATCHING KEY
 * ────────────
 *   external_statement_entries.external_reference
 *       == transactions.transaction_ref  (UUID string)
 *
 * AMOUNT COMPARED
 * ───────────────
 *   Internal: SUM(ledger_entries.amount WHERE entry_type = 'DEBIT'
 *                 AND transaction_id = matched_transaction.id)
 *   NOTE: assumes single-currency transactions. If mixed-currency postings
 *   are ever supported, this sum must be grouped by currency and compared
 *   against a per-currency external amount.
 *
 * TOLERANCES
 * ──────────
 *   Amount  : |internal_debit_sum − external_amount| ≤ amountTolerance
 *             default 0.05, configurable, per-currency deferred to Phase 4.
 *   Date    : |posted_at − statement_date| informational only — recorded
 *             in match details but never blocks a match.
 *
 * GRACE PERIOD (MISSING_EXTERNAL only)
 * ──────────────────────────────────────
 *   A transaction is eligible for MISSING_EXTERNAL only if
 *   posted_at < now() − gracePeriodHours.  Default: 24 h.
 *   Set to 0 via test property so integration tests are not time-gated.
 *   No grace period for MISSING_INTERNAL.
 *
 * DECISION TREE
 * ─────────────
 *   ── PASS 1: for each unmatched internal POSTED transaction T ──
 *   (unmatched = not in reconciliation_matches; includes those with
 *    existing OPEN exceptions — they may now resolve)
 *
 *     Look up external_statement_entries WHERE external_reference = T.transaction_ref
 *
 *     ┌── No external row found
 *     │     → INSERT reconciliation_exceptions ON CONFLICT DO NOTHING
 *     │         exception_type = MISSING_EXTERNAL, status = OPEN
 *     │
 *     └── External row E found
 *
 *           |T.debit_sum − E.amount| ≤ amountTolerance ?
 *
 *           ┌── YES → MATCH
 *           │     1. INSERT reconciliation_matches (amounts, deltas, matched_at)
 *           │     2. SELF-RESOLUTION (same @Transactional boundary):
 *           │            UPDATE reconciliation_exceptions
 *           │            SET status = 'RESOLVED', resolved_at = now()
 *           │            WHERE transaction_ref = T.transaction_ref AND status = 'OPEN'
 *           │        Resolves prior MISSING_EXTERNAL or AMOUNT_MISMATCH atomically.
 *           │
 *           └── NO  → AMOUNT_MISMATCH
 *                 → INSERT reconciliation_exceptions ON CONFLICT DO NOTHING
 *
 *   ── PASS 2: for each unmatched external entry E with no internal transaction ──
 *     → INSERT reconciliation_exceptions ON CONFLICT DO NOTHING
 *         exception_type = MISSING_INTERNAL
 *
 * IDEMPOTENCY
 * ───────────
 *   All INSERTs use ON CONFLICT DO NOTHING backed by partial UNIQUE indexes.
 *   Running the engine twice on unchanged data is safe.
 *
 * STORAGE — reconciliation_matches TABLE (not reconciled_at columns)
 * ──────────────────────────────────────────────────────────────────
 *   Separate table keeps ledger-api's core schema unchanged.
 * ════════════════════════════════════════════════════════════════════════
 */
@Service
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    private final InternalLedgerRepository       ledgerRepo;
    private final ExternalStatementRepository    externalRepo;
    private final ReconciliationMatchRepository  matchRepo;
    private final ReconciliationExceptionRepository exceptionRepo;
    private final ObjectMapper                   objectMapper;

    @Value("${reconciliation.grace-period-hours:24}")
    private int gracePeriodHours;

    @Value("${reconciliation.amount-tolerance:0.05}")
    private BigDecimal amountTolerance;

    public ReconciliationEngine(InternalLedgerRepository ledgerRepo,
                                ExternalStatementRepository externalRepo,
                                ReconciliationMatchRepository matchRepo,
                                ReconciliationExceptionRepository exceptionRepo,
                                ObjectMapper objectMapper) {
        this.ledgerRepo    = ledgerRepo;
        this.externalRepo  = externalRepo;
        this.matchRepo     = matchRepo;
        this.exceptionRepo = exceptionRepo;
        this.objectMapper  = objectMapper;
    }

    /**
     * Execute one full reconciliation pass.
     *
     * Designed to be called either by {@link ReconciliationScheduler} (on a cron)
     * or directly in integration tests (synchronously, deterministic).
     *
     * The entire method runs in a single @Transactional boundary so that:
     *   - match INSERT and exception RESOLVE are always atomic
     *   - a crash mid-run leaves no partial state visible to concurrent readers
     *     (Postgres MVCC — readers see the pre-run snapshot until commit)
     */
    @Transactional
    public ReconciliationRunResult reconcileOnce() {
        int matched = 0, missingExternal = 0, missingInternal = 0,
            amountMismatch = 0, resolved = 0;

        // ── Pass 1: internal transactions → external entries ─────────────────
        List<UnmatchedTransaction> candidates =
                ledgerRepo.findUnmatchedPosted(gracePeriodHours);

        log.debug("Reconciliation Pass 1: {} unmatched transaction(s) to process", candidates.size());

        for (UnmatchedTransaction txn : candidates) {

            Optional<ExternalStatementEntry> extOpt =
                    externalRepo.findByExternalReference(txn.transactionRef());

            if (extOpt.isEmpty()) {
                // ── MISSING_EXTERNAL ─────────────────────────────────────────
                exceptionRepo.insertIfAbsent(
                        ExceptionType.MISSING_EXTERNAL,
                        txn.transactionRef(),
                        null,
                        txn.internalDebitSum(),
                        null,
                        toJson(Map.of(
                                "posted_at",       txn.postedAt().toString(),
                                "internal_amount", txn.internalDebitSum().toPlainString()
                        )));
                missingExternal++;
                log.debug("MISSING_EXTERNAL: transactionRef={}", txn.transactionRef());
                continue;
            }

            ExternalStatementEntry ext = extOpt.get();

            // NOTE: assumes single-currency transaction — see UnmatchedTransaction javadoc.
            BigDecimal delta     = txn.internalDebitSum().subtract(ext.amount()).abs();
            long dateDeltaDays   = Math.abs(ChronoUnit.DAYS.between(
                                       txn.postedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                                       ext.statementDate()));

            if (delta.compareTo(amountTolerance) <= 0) {

                // ── MATCH ────────────────────────────────────────────────────
                matchRepo.insert(
                        txn.transactionRef(),
                        ext.externalReference(),
                        txn.internalDebitSum(),
                        ext.amount(),
                        delta,
                        (int) dateDeltaDays);
                matched++;

                // ── SELF-RESOLUTION (same @Transactional boundary) ───────────
                // Resolves any prior MISSING_EXTERNAL or AMOUNT_MISMATCH for
                // this transactionRef. The UPDATE and the match INSERT above
                // commit atomically — no partial state is ever observable.
                int r = exceptionRepo.resolveOpenByTransactionRef(txn.transactionRef());
                resolved += r;
                if (r > 0) {
                    log.info("RESOLVED {} exception(s) for transactionRef={} — match found",
                             r, txn.transactionRef());
                }
                log.debug("MATCHED: transactionRef={} externalRef={} delta={} dateDelta={}d",
                          txn.transactionRef(), ext.externalReference(), delta, dateDeltaDays);

            } else {

                // ── AMOUNT_MISMATCH ───────────────────────────────────────────
                exceptionRepo.insertIfAbsent(
                        ExceptionType.AMOUNT_MISMATCH,
                        txn.transactionRef(),
                        ext.externalReference(),
                        txn.internalDebitSum(),
                        ext.amount(),
                        toJson(Map.of(
                                "internal_amount", txn.internalDebitSum().toPlainString(),
                                "external_amount", ext.amount().toPlainString(),
                                "delta",           delta.toPlainString(),
                                "tolerance_used",  amountTolerance.toPlainString(),
                                "statement_date",  ext.statementDate().toString(),
                                "date_delta_days", String.valueOf(dateDeltaDays)
                        )));
                amountMismatch++;
                log.debug("AMOUNT_MISMATCH: transactionRef={} internalAmount={} externalAmount={} delta={}",
                          txn.transactionRef(), txn.internalDebitSum(), ext.amount(), delta);
            }
        }

        // ── Pass 2: external entries → internal transactions ─────────────────
        List<ExternalStatementEntry> orphanExternal =
                externalRepo.findUnmatchedWithNoInternalTransaction();

        log.debug("Reconciliation Pass 2: {} orphan external entry(ies) to process",
                  orphanExternal.size());

        for (ExternalStatementEntry ext : orphanExternal) {
            exceptionRepo.insertIfAbsent(
                    ExceptionType.MISSING_INTERNAL,
                    null,
                    ext.externalReference(),
                    null,
                    ext.amount(),
                    toJson(Map.of(
                            "external_amount", ext.amount().toPlainString(),
                            "currency",        ext.currency(),
                            "statement_date",  ext.statementDate().toString()
                    )));
            missingInternal++;
            log.debug("MISSING_INTERNAL: externalRef={}", ext.externalReference());
        }

        ReconciliationRunResult result = new ReconciliationRunResult(
                matched, resolved, missingExternal, missingInternal, amountMismatch);
        log.info("Reconciliation run complete: {}", result);
        return result;
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            // Should never happen with a plain string map
            log.warn("Failed to serialise exception details to JSON", e);
            return "{}";
        }
    }
}
