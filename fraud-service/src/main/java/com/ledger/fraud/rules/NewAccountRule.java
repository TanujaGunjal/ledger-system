package com.ledger.fraud.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * New-account rule: flags transactions on recently created accounts.
 *
 * WHY this matters: newly opened accounts used for large immediate transfers are
 * a classic synthetic-identity fraud pattern. Fraudsters open an account, fund it
 * briefly to establish apparent legitimacy, and then drain it before controls
 * catch up. A high-value transaction within the first hour of account creation
 * is a very strong signal.
 *
 * Scoring (configurable via {@code fraud.rules.new-account.*} in application.yml):
 * <ul>
 *   <li>Account created &lt; 1 hour ago → +40 points (regardless of amount).</li>
 *   <li>Account created &lt; 24 hours ago AND amount &gt; 1000 → +20 points.</li>
 * </ul>
 *
 * The two checks are mutually exclusive (first match wins) to avoid double-scoring
 * a brand-new account with a large amount.
 *
 * Implementation: reads accounts table (read-only). No writes.
 */
@Component
public class NewAccountRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(NewAccountRule.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${fraud.rules.new-account.high-risk-hours:1}")
    private int highRiskHours;

    @Value("${fraud.rules.new-account.medium-risk-hours:24}")
    private int mediumRiskHours;

    @Value("${fraud.rules.new-account.medium-risk-amount:1000}")
    private BigDecimal mediumRiskAmount;

    public NewAccountRule(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "NewAccountRule";
    }

    /**
     * Look up the account's {@code created_at} timestamp and compare it to the
     * current time. If the account is younger than the configured thresholds,
     * assign points accordingly.
     *
     * Returns noTrigger() if the account doesn't exist (defensive) or if neither
     * threshold is met.
     */
    @Override
    public RuleResult evaluate(RuleContext context) {
        Instant createdAt = fetchAccountCreatedAt(context.accountId());
        if (createdAt == null) {
            // Account not found — this shouldn't happen for a valid transaction,
            // but defensively skip rather than throw.
            log.warn("NewAccountRule: account not found for accountId={}", context.accountId());
            return RuleResult.noTrigger();
        }

        Instant now = Instant.now();
        long hoursOld = ChronoUnit.HOURS.between(createdAt, now);

        log.debug("NewAccountRule: accountId={} hoursOld={}", context.accountId(), hoursOld);

        // ── Very new account (< high-risk-hours old) ─────────────────────────
        if (hoursOld < highRiskHours) {
            String reason = String.format(
                "Very new account: created %d hour(s) ago (threshold: <%d hour(s))",
                hoursOld, highRiskHours);
            return new RuleResult(40, reason);
        }

        // ── Moderately new account with large amount ──────────────────────────
        if (hoursOld < mediumRiskHours && context.amount().compareTo(mediumRiskAmount) > 0) {
            String reason = String.format(
                "New account (created %d hour(s) ago) with large amount %s %s (threshold: >%s)",
                hoursOld, context.amount().toPlainString(), context.currency(),
                mediumRiskAmount.toPlainString());
            return new RuleResult(20, reason);
        }

        return RuleResult.noTrigger();
    }

    private Instant fetchAccountCreatedAt(Long accountId) {
        return jdbcTemplate.query(
            "SELECT created_at FROM accounts WHERE id = ?",
            ps -> ps.setLong(1, accountId),
            rs -> {
                if (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    return (ts != null) ? ts.toInstant() : null;
                }
                return null;
            }
        );
    }
}
