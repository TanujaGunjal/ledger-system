package com.ledger.fraud.domain;

/**
 * Risk level classification assigned to a scored transaction.
 *
 * The thresholds that map a numeric score to a RiskLevel are configurable via
 * {@code fraud.risk.medium-score-threshold} and {@code fraud.risk.high-score-threshold}
 * in {@code application.yml}. This enum is purely a label — the boundary logic
 * lives in {@link com.ledger.fraud.service.FraudScoringEngine}.
 *
 * <ul>
 *   <li>{@code LOW}    — score 0–30: transaction is consistent with normal behaviour.</li>
 *   <li>{@code MEDIUM} — score 31–69: elevated risk; transaction proceeds but is flagged.</li>
 *   <li>{@code HIGH}   — score 70–100: transaction should be blocked pre-posting.</li>
 * </ul>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
