package com.ledger.fraud.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Amount threshold rule: detects transactions that are unusually large in absolute
 * terms or that consume a dangerous fraction of the account's current balance.
 *
 * WHY this matters: large single transfers are disproportionately represented in
 * fraud losses. A transaction that drains ≥80% of a balance in one shot is a
 * strong signal of account takeover. Absolute large-amount thresholds catch
 * wire fraud and synthetic-identity schemes regardless of balance.
 *
 * Scoring (configurable via {@code fraud.rules.amount.*} in application.yml):
 * <ul>
 *   <li>Amount &gt; 100,000 → +50 points (override — this alone reaches HIGH risk
 *       when combined with any other signal, and MEDIUM on its own).</li>
 *   <li>Amount &gt; 50,000 → +20 points (additive with balance-ratio check).</li>
 *   <li>Amount &gt; 80% of current balance → +30 points.</li>
 * </ul>
 *
 * Maximum possible contribution from this rule: 50 points (the very-large-transaction
 * override replaces rather than adds to the large-transaction score).
 *
 * Implementation: reads account_balances table (read-only). No writes.
 */
@Component
public class AmountThresholdRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(AmountThresholdRule.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${fraud.rules.amount.large-transaction:50000}")
    private BigDecimal largeTransactionThreshold;

    @Value("${fraud.rules.amount.very-large-transaction:100000}")
    private BigDecimal veryLargeTransactionThreshold;

    @Value("${fraud.rules.amount.balance-ratio-threshold:0.80}")
    private double balanceRatioThreshold;

    public AmountThresholdRule(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String name() {
        return "AmountThresholdRule";
    }

    /**
     * Evaluate the transaction amount against absolute thresholds and against the
     * current account balance. Returns the highest applicable score.
     *
     * The very-large-transaction check (100k+) is treated as an override:
     * it returns immediately with 50 points rather than accumulating with
     * the large-transaction check, preventing double-counting.
     */
    @Override
    public RuleResult evaluate(RuleContext context) {
        BigDecimal amount = context.amount();

        // ── Absolute very-large-transaction check (override) ─────────────────
        if (amount.compareTo(veryLargeTransactionThreshold) > 0) {
            String reason = String.format(
                "Very large transaction: %s %s exceeds threshold of %s",
                amount.toPlainString(), context.currency(),
                veryLargeTransactionThreshold.toPlainString());
            log.debug("AmountThresholdRule: VERY_LARGE accountId={} amount={}", context.accountId(), amount);
            return new RuleResult(50, reason);
        }

        int score = 0;
        StringBuilder reason = new StringBuilder();

        // ── Absolute large-transaction check ─────────────────────────────────
        if (amount.compareTo(largeTransactionThreshold) > 0) {
            score += 20;
            reason.append(String.format("Large transaction: %s %s exceeds %s. ",
                amount.toPlainString(), context.currency(),
                largeTransactionThreshold.toPlainString()));
            log.debug("AmountThresholdRule: LARGE accountId={} amount={}", context.accountId(), amount);
        }

        // ── Balance-ratio check ───────────────────────────────────────────────
        // Fetch current balance for this account. If no balance row exists (e.g.
        // account was just created and has no initial balance yet), skip this check.
        Optional<BigDecimal> balanceOpt = fetchBalance(context.accountId());
        if (balanceOpt.isPresent()) {
            BigDecimal balance = balanceOpt.get();
            // Avoid division-by-zero on zero/negative balance accounts.
            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                double ratio = amount.doubleValue() / balance.doubleValue();
                if (ratio > balanceRatioThreshold) {
                    score += 30;
                    reason.append(String.format(
                        "High balance consumption: %.0f%% of balance (%s %s).",
                        ratio * 100, balance.toPlainString(), context.currency()));
                    log.debug("AmountThresholdRule: HIGH_RATIO accountId={} ratio={:.2f}", context.accountId(), ratio);
                }
            }
        }

        if (score == 0) {
            return RuleResult.noTrigger();
        }
        return new RuleResult(score, reason.toString().trim());
    }

    private Optional<BigDecimal> fetchBalance(Long accountId) {
        return jdbcTemplate.query(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            ps -> ps.setLong(1, accountId),
            rs -> {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getBigDecimal("balance"));
                }
                return Optional.empty();
            }
        );
    }
}
