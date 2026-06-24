package com.ledger.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable domain object representing a persisted fraud investigation case.
 *
 * A {@code FraudCase} is created whenever the scoring engine returns MEDIUM or HIGH
 * risk for a Kafka-consumed {@code TransactionPosted} event. LOW risk transactions
 * are scored but not persisted to avoid flooding the table with noise.
 *
 * Lifecycle: OPEN → REVIEWED (analyst confirmed fraud) or DISMISSED (analyst cleared it).
 *
 * This is a Java Record — immutable by design with no boilerplate. The field names
 * map directly to the {@code fraud_cases} table columns via JdbcTemplate row mappers
 * in {@link com.ledger.fraud.repository.FraudCaseRepository}.
 */
public record FraudCase(
        Long id,

        /** UUID string matching transactions.transaction_ref in the ledger. */
        String transactionRef,

        /** Account ID on the DEBIT side of the transaction. */
        Long accountId,

        /** Composite risk classification for this case. */
        RiskLevel riskLevel,

        /** Composite score 0–100 summed across all rules. */
        int score,

        /** Comma-separated names of rules that contributed a non-zero score. */
        String triggeredRules,

        /** Current lifecycle status: OPEN, REVIEWED, or DISMISSED. */
        String status,

        /** Amount on the DEBIT entry that triggered scoring. May be null if unknown. */
        BigDecimal amount,

        /** ISO 4217 currency code of the DEBIT entry. May be null if unknown. */
        String currency,

        /** JSON string with rule-specific context (reasons, balance snapshots, etc.). */
        String details,

        Instant createdAt,

        /** Set when an analyst reviews or dismisses the case; null while OPEN. */
        Instant reviewedAt
) {}
