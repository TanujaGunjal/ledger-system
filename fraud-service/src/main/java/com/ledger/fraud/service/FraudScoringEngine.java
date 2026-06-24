package com.ledger.fraud.service;

import com.ledger.fraud.domain.FraudScore;
import com.ledger.fraud.domain.RiskLevel;
import com.ledger.fraud.rules.FraudRule;
import com.ledger.fraud.rules.FraudRule.RuleContext;
import com.ledger.fraud.rules.FraudRule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates all registered {@link FraudRule} implementations and aggregates
 * their scores into a single {@link FraudScore}.
 *
 * Design decisions:
 * <ul>
 *   <li><strong>Degraded-mode safety</strong>: if a rule throws any exception, the
 *       engine logs the error, marks the result as degraded, and continues with the
 *       remaining rules. If ALL rules fail, the engine returns LOW risk with
 *       {@code degradedMode=true} — it never throws, never blocks a transaction.</li>
 *   <li><strong>Score cap</strong>: the total score is capped at 100 so that no
 *       combination of rules can produce an out-of-range value.</li>
 *   <li><strong>Stateless</strong>: no instance state is mutated between calls.
 *       The rule list is injected once at construction time by Spring's
 *       {@code List<FraudRule>} collection injection — adding a new rule bean
 *       automatically includes it here with zero changes to this class.</li>
 * </ul>
 */
@Service
public class FraudScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringEngine.class);
    private static final int MAX_SCORE = 100;

    private final List<FraudRule> rules;

    @Value("${fraud.risk.medium-score-threshold:31}")
    private int mediumScoreThreshold;

    @Value("${fraud.risk.high-score-threshold:70}")
    private int highScoreThreshold;

    /**
     * Spring injects all beans implementing {@link FraudRule} into this list.
     * This means registering a new rule is as simple as annotating it with
     * {@code @Component} — no changes needed here.
     */
    public FraudScoringEngine(List<FraudRule> rules) {
        this.rules = rules;
    }

    /**
     * Score a transaction by running all registered rules.
     *
     * @param transactionRef UUID string of the transaction (used only for logging).
     * @param accountId      Account ID on the DEBIT side.
     * @param amount         Amount of the DEBIT entry.
     * @param currency       Currency of the DEBIT entry.
     * @return A {@link FraudScore} — never null, never throws.
     */
    public FraudScore score(String transactionRef, Long accountId, BigDecimal amount, String currency) {

        RuleContext context = new RuleContext(accountId, amount, currency, transactionRef);

        List<String> triggeredRules = new ArrayList<>();
        List<String> reasons        = new ArrayList<>();
        int totalScore              = 0;
        boolean degradedMode        = false;
        int failedRules             = 0;

        for (FraudRule rule : rules) {
            try {
                RuleResult result = rule.evaluate(context);
                if (result.triggered()) {
                    triggeredRules.add(rule.name());
                    if (!result.reason().isBlank()) {
                        reasons.add(result.reason());
                    }
                    totalScore += result.score();
                }
                log.debug("Rule {} → score={} triggered={} accountId={} txnRef={}",
                          rule.name(), result.score(), result.triggered(), accountId, transactionRef);
            } catch (Exception e) {
                // This rule failed — log it, mark degraded, and continue with the rest.
                // We must never let a rule exception propagate to the caller because that
                // would either block the transaction (in the pre-posting path) or cause
                // the Kafka consumer to retry forever (in the async path).
                log.error("Rule {} threw an exception for accountId={} txnRef={} — skipping: {}",
                          rule.name(), accountId, transactionRef, e.getMessage(), e);
                failedRules++;
                degradedMode = true;
            }
        }

        // If every single rule failed, return LOW with degraded flag.
        // Returning HIGH in a fully degraded state would cause false positives
        // that block legitimate transactions — the safer default is LOW.
        if (!rules.isEmpty() && failedRules == rules.size()) {
            log.warn("ALL {} rules failed for accountId={} txnRef={} — returning LOW (degraded)",
                     rules.size(), accountId, transactionRef);
            return new FraudScore(RiskLevel.LOW, 0, List.of(), List.of(), true);
        }

        // Cap the score at 100.
        int cappedScore = Math.min(totalScore, MAX_SCORE);

        RiskLevel riskLevel = classifyRisk(cappedScore);

        log.info("Scored txnRef={} accountId={} score={} riskLevel={} rules={} degraded={}",
                 transactionRef, accountId, cappedScore, riskLevel, triggeredRules, degradedMode);

        return new FraudScore(riskLevel, cappedScore, triggeredRules, reasons, degradedMode);
    }

    /**
     * Map a numeric score to a {@link RiskLevel} using the configured thresholds.
     * Thresholds are read from {@code fraud.risk.*} in application.yml.
     */
    private RiskLevel classifyRisk(int score) {
        if (score >= highScoreThreshold) {
            return RiskLevel.HIGH;
        } else if (score >= mediumScoreThreshold) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }
}
