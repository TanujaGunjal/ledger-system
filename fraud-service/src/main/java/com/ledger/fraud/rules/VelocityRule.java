package com.ledger.fraud.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Velocity rule: detects unusually high transaction frequency on a single account
 * within a short time window.
 *
 * WHY this matters: fraudsters often conduct rapid small test transactions before
 * a large fraudulent transfer, or use compromised accounts for burst card-not-present
 * attacks. A sudden spike in DEBIT frequency on an account is a strong signal.
 *
 * Scoring (configurable via {@code fraud.rules.velocity.*} in application.yml):
 * <ul>
 *   <li>3–(high-threshold-1) DEBITs in the window: +20 points (medium velocity)</li>
 *   <li>high-threshold–(high-threshold*2-1) DEBITs: +40 points (high velocity)</li>
 *   <li>≥ high-threshold*2 DEBITs, OR ≥ 8 by default: +60 points (extreme velocity)</li>
 * </ul>
 *
 * The actual thresholds applied:
 * <ul>
 *   <li>window-minutes: look-back period (default 10)</li>
 *   <li>medium-threshold: first trigger at N debits (default 3 → 3–4 = +20)</li>
 *   <li>high-threshold: extreme trigger at N debits (default 8 → 8+ = +60)</li>
 * </ul>
 *
 * Implementation: reads ledger_entries table directly (read-only). No writes.
 */
@Component
public class VelocityRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(VelocityRule.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${fraud.rules.velocity.window-minutes:10}")
    private int windowMinutes;

    @Value("${fraud.rules.velocity.medium-threshold:3}")
    private int mediumThreshold;

    @Value("${fraud.rules.velocity.high-threshold:8}")
    private int highThreshold;

    public VelocityRule(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "VelocityRule";
    }

    /**
     * Count DEBIT entries for this account in the last {@code windowMinutes} minutes
     * and assign a score based on the count.
     *
     * Queries {@code ledger_entries} joined to {@code transactions} (to get created_at)
     * for DEBIT entries on the given account within the configured look-back window.
     */
    @Override
    public RuleResult evaluate(RuleContext context) {
        // Count how many DEBIT ledger entries exist for this account in the time window.
        // We look at ledger_entries.created_at directly — it is set to now() at insert time
        // and is indexed implicitly via the account_id FK.
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledger_entries le
            WHERE le.account_id = ?
              AND le.entry_type  = 'DEBIT'
              AND le.created_at >= now() - (? || ' minutes')::interval
            """,
            Integer.class,
            context.accountId(),
            windowMinutes
        );

        int debitCount = (count != null) ? count : 0;
        log.debug("VelocityRule: accountId={} debitCount={} window={}min",
                  context.accountId(), debitCount, windowMinutes);

        if (debitCount >= highThreshold) {
            // Extreme velocity — 8+ debits in window: maximum score contribution.
            String reason = String.format(
                "Extreme velocity: %d DEBIT entries in last %d minutes (threshold: %d)",
                debitCount, windowMinutes, highThreshold);
            return new RuleResult(60, reason);
        } else if (debitCount >= mediumThreshold + 2) {
            // High velocity — 5–7 debits in window.
            String reason = String.format(
                "High velocity: %d DEBIT entries in last %d minutes",
                debitCount, windowMinutes);
            return new RuleResult(40, reason);
        } else if (debitCount >= mediumThreshold) {
            // Medium velocity — 3–4 debits in window.
            String reason = String.format(
                "Elevated velocity: %d DEBIT entries in last %d minutes",
                debitCount, windowMinutes);
            return new RuleResult(20, reason);
        }

        return RuleResult.noTrigger();
    }
}
