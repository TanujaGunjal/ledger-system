package com.ledger.fraud.rules;

import java.math.BigDecimal;

/**
 * Contract for a single fraud detection rule.
 *
 * Each rule is responsible for evaluating one aspect of a transaction (e.g. velocity,
 * amount, account age) and returning a {@link RuleResult} containing the score
 * contribution (0 to its maximum) and a human-readable reason string.
 *
 * Rules are stateless — they receive all needed context via the {@link RuleContext}
 * parameter and perform their own database reads. Statelessness makes rules easy to
 * unit test in isolation (just mock JdbcTemplate) and easy to add/remove at runtime
 * via Spring's {@code @Autowired List<FraudRule>} collection injection.
 *
 * The scoring engine ({@link com.ledger.fraud.service.FraudScoringEngine}) wraps each
 * call in a try-catch so that a single failing rule does not abort the entire evaluation.
 */
public interface FraudRule {

    /**
     * Human-readable name of this rule, used in {@code fraud_cases.triggered_rules}
     * and in log messages. Should be a stable, short identifier.
     */
    String name();

    /**
     * Evaluate this rule for the given transaction context.
     *
     * @param context All inputs the rule may need (account ID, amount, etc.)
     * @return A {@link RuleResult} with a score ≥ 0 and an optional reason string.
     *         Return score=0 and no reason when the rule does not trigger.
     */
    RuleResult evaluate(RuleContext context);

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * All inputs a rule may need to make its determination.
     *
     * @param accountId      Account ID on the DEBIT side of the transaction.
     * @param amount         Amount of the DEBIT entry.
     * @param currency       Currency of the DEBIT entry.
     * @param transactionRef UUID string of the parent transaction (for logging).
     */
    record RuleContext(
            Long accountId,
            BigDecimal amount,
            String currency,
            String transactionRef
    ) {}

    /**
     * Outcome of a single rule evaluation.
     *
     * @param score  Points contributed by this rule (0 if rule did not trigger).
     * @param reason Human-readable explanation; empty string if score is 0.
     */
    record RuleResult(int score, String reason) {

        /** Convenience factory for a rule that did not trigger. */
        public static RuleResult noTrigger() {
            return new RuleResult(0, "");
        }

        /** Returns true if this rule contributed any score. */
        public boolean triggered() {
            return score > 0;
        }
    }
}
