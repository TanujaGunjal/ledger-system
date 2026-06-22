package com.ledger.reconciliation.service;

import com.ledger.reconciliation.domain.UnmatchedTransaction;
import com.ledger.reconciliation.repository.ExternalStatementRepository;
import com.ledger.reconciliation.repository.InternalLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Synthetic external feed generator — simulates the data that would normally
 * arrive from a real bank or payment processor.
 *
 * Runs on a fixed-delay schedule (default: every 30 seconds). For each POSTED
 * transaction that does not yet have an external_statement_entries row, it
 * generates a synthetic entry according to the following distribution:
 *
 *   85% — exact match  (same reference, amount, and date as the internal transaction)
 *    5% — amount off   (same reference and date, but amount differs by $0.01–$0.05)
 *    5% — delayed      (same reference and amount, but statement_date is 2–6 days earlier)
 *    5% — omitted      (no external entry generated at all)
 *
 * Additionally generates a small number of ORPHAN entries (no matching internal
 * transaction) to exercise the MISSING_INTERNAL path. Capped at 3 orphans total
 * to avoid unbounded growth during long test or demo runs.
 *
 * Only active when reconciliation.scheduling.enabled=true (default).
 * Disabled via test application.yml so integration tests call reconcileOnce()
 * directly without concurrent feed mutations.
 */
@Component
@ConditionalOnProperty(name = "reconciliation.scheduling.enabled",
                       havingValue = "true", matchIfMissing = true)
public class MockFeedGenerator {

    private static final Logger log = LoggerFactory.getLogger(MockFeedGenerator.class);
    private static final Random RANDOM = new Random();

    private final InternalLedgerRepository   ledgerRepo;
    private final ExternalStatementRepository externalRepo;

    public MockFeedGenerator(InternalLedgerRepository ledgerRepo,
                             ExternalStatementRepository externalRepo) {
        this.ledgerRepo   = ledgerRepo;
        this.externalRepo = externalRepo;
    }

    @Scheduled(fixedDelayString = "${reconciliation.schedule.feed-generator-ms:30000}",
               initialDelayString = "${reconciliation.schedule.feed-generator-initial-ms:5000}")
    @Transactional
    public void generateFeed() {
        List<UnmatchedTransaction> pending = ledgerRepo.findPostedWithNoExternalEntry();
        if (pending.isEmpty()) {
            return;
        }

        int exact = 0, rounding = 0, delayed = 0, omitted = 0;

        for (UnmatchedTransaction txn : pending) {
            int roll = RANDOM.nextInt(100);
            LocalDate baseDate = txn.postedAt()
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate();

            if (roll < 85) {
                // 85%: exact match
                insertSynthetic(txn.transactionRef(), txn.internalDebitSum(),
                                txn.currency(), baseDate);
                exact++;

            } else if (roll < 90) {
                // 5%: rounding difference ($0.01–$0.05) — exercises AMOUNT_MISMATCH
                BigDecimal diff = BigDecimal.valueOf(RANDOM.nextInt(5) + 1, 2); // 0.01–0.05
                insertSynthetic(txn.transactionRef(),
                                txn.internalDebitSum().add(diff),
                                txn.currency(), baseDate);
                rounding++;

            } else if (roll < 95) {
                // 5%: delayed statement_date (2–6 days earlier) — exercises date-delta path
                int days = RANDOM.nextInt(5) + 2;
                insertSynthetic(txn.transactionRef(), txn.internalDebitSum(),
                                txn.currency(), baseDate.minusDays(days));
                delayed++;

            } else {
                // 5%: omitted — no external entry; exercises MISSING_EXTERNAL
                omitted++;
            }
        }

        // Generate a small number of orphan entries (MISSING_INTERNAL path).
        // Capped at 3 to avoid unbounded growth.
        int currentOrphans = externalRepo.countOrphans();
        if (currentOrphans < 3) {
            String orphanRef = "ORPHAN-" + UUID.randomUUID().toString().substring(0, 8);
            insertSynthetic(orphanRef, BigDecimal.valueOf(99, 2), "USD", LocalDate.now());
            log.debug("Generated orphan external entry: ref={}", orphanRef);
        }

        log.info("MockFeedGenerator: processed {} transactions — exact={} rounding={} delayed={} omitted={}",
                 pending.size(), exact, rounding, delayed, omitted);
    }

    private void insertSynthetic(String externalReference, BigDecimal amount,
                                 String currency, LocalDate statementDate) {
        String rawPayload = String.format(
                "{\"synthetic\":true,\"ref\":\"%s\",\"amount\":\"%s\"}",
                externalReference, amount.toPlainString());
        externalRepo.insertIfAbsent(externalReference, amount, currency,
                                    statementDate, rawPayload);
    }
}
