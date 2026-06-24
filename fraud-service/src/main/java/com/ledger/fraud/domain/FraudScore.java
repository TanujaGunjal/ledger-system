package com.ledger.fraud.domain;

import java.util.List;

/**
 * Value object carrying the output of a single scoring engine evaluation.
 *
 * {@code FraudScore} is produced by {@link com.ledger.fraud.service.FraudScoringEngine}
 * and consumed by both the REST controller (for the synchronous /score endpoint) and
 * the Kafka consumer (for asynchronous event-driven scoring). It is intentionally not
 * persisted directly — instead, the caller decides whether to create a {@link FraudCase}
 * from it based on the risk level.
 *
 * @param riskLevel       Classified risk level (HIGH/MEDIUM/LOW) derived from totalScore.
 * @param totalScore      Composite score 0–100 capped after summing all rule contributions.
 * @param triggeredRules  Names of rules that fired (contributed a non-zero score).
 * @param reasons         Human-readable explanation strings from each triggered rule.
 * @param degradedMode    True if one or more rules threw an exception and were skipped.
 *                        When true, the score may be under-estimated — callers should
 *                        log a warning but must not block the transaction on this alone.
 */
public record FraudScore(
        RiskLevel riskLevel,
        int totalScore,
        List<String> triggeredRules,
        List<String> reasons,
        boolean degradedMode
) {

    /**
     * Derive the recommendation string for the REST response based on the risk level.
     * ALLOW / REVIEW / BLOCK maps cleanly to LOW / MEDIUM / HIGH.
     */
    public String recommendation() {
        return switch (riskLevel) {
            case HIGH   -> "BLOCK";
            case MEDIUM -> "REVIEW";
            case LOW    -> "ALLOW";
        };
    }
}
