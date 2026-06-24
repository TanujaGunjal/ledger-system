package com.ledger.fraud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.fraud.domain.FraudCase;
import com.ledger.fraud.domain.FraudScore;
import com.ledger.fraud.domain.RiskLevel;
import com.ledger.fraud.repository.FraudCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Business-logic façade for fraud case lifecycle management.
 *
 * Responsibilities:
 * <ol>
 *   <li>Coordinating between {@link FraudScoringEngine} and {@link FraudCaseRepository}:
 *       score a transaction and persist a case if the risk is MEDIUM or HIGH.</li>
 *   <li>Providing query methods consumed by the REST controller.</li>
 *   <li>Handling analyst review actions (CONFIRM → REVIEWED, DISMISS → DISMISSED).</li>
 * </ol>
 *
 * This class is {@code @Transactional} because the create-case path involves a
 * read (idempotency check) followed by a conditional write. Wrapping both in a
 * single transaction prevents a race where two Kafka partitions deliver the same
 * message concurrently and both pass the existence check before either inserts.
 */
@Service
@Transactional
public class FraudCaseService {

    private static final Logger log = LoggerFactory.getLogger(FraudCaseService.class);

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudScoringEngine  scoringEngine;
    private final ObjectMapper        objectMapper;

    public FraudCaseService(FraudCaseRepository fraudCaseRepository,
                            FraudScoringEngine scoringEngine,
                            ObjectMapper objectMapper) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.scoringEngine       = scoringEngine;
        this.objectMapper        = objectMapper;
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    /**
     * Score a transaction and, if the risk level is MEDIUM or HIGH, persist a
     * {@link FraudCase}. LOW-risk transactions are scored but not stored to avoid
     * table bloat.
     *
     * Idempotent: if a fraud case already exists for the given {@code transactionRef},
     * the insert is skipped and the existing score is re-returned from the database.
     * This ensures Kafka redeliveries don't create duplicate cases.
     *
     * @return The {@link FraudScore} from the engine (always present, never null).
     */
    public FraudScore scoreAndPersist(String transactionRef,
                                      Long accountId,
                                      BigDecimal amount,
                                      String currency) {

        FraudScore score = scoringEngine.score(transactionRef, accountId, amount, currency);

        if (score.riskLevel() == RiskLevel.LOW) {
            log.debug("scoreAndPersist: LOW risk txnRef={} — no case persisted", transactionRef);
            return score;
        }

        // ── Idempotency check ─────────────────────────────────────────────────
        // This check + insert runs inside the @Transactional boundary, so concurrent
        // executions for the same transactionRef will serialize here. The second
        // caller will see the row inserted by the first and skip its own insert.
        if (fraudCaseRepository.existsByTransactionRef(transactionRef)) {
            log.info("scoreAndPersist: case already exists for txnRef={} — skipping insert", transactionRef);
            return score;
        }

        // ── Persist the case ─────────────────────────────────────────────────
        String triggeredRulesStr = String.join(",", score.triggeredRules());
        String detailsJson       = buildDetailsJson(score);

        long id = fraudCaseRepository.insert(
            transactionRef,
            accountId,
            score.riskLevel(),
            score.totalScore(),
            triggeredRulesStr,
            amount,
            currency,
            detailsJson
        );

        if (score.riskLevel() == RiskLevel.HIGH) {
            // In production this would trigger a notification (SMS, PagerDuty, etc.).
            // For now we log at WARN so it stands out in the log stream.
            log.warn("HIGH RISK FRAUD CASE CREATED — id={} txnRef={} accountId={} score={}",
                     id, transactionRef, accountId, score.totalScore());
        } else {
            log.info("MEDIUM risk fraud case created — id={} txnRef={} accountId={} score={}",
                     id, transactionRef, accountId, score.totalScore());
        }

        return score;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Return all cases for a given status filter ("OPEN", "ALL", "REVIEWED", "DISMISSED"). */
    @Transactional(readOnly = true)
    public List<FraudCase> getCasesByStatus(String status) {
        return fraudCaseRepository.findByStatus(status);
    }

    /** Return all cases for a given risk level ("HIGH", "MEDIUM", "LOW"). */
    @Transactional(readOnly = true)
    public List<FraudCase> getCasesByRiskLevel(String riskLevel) {
        return fraudCaseRepository.findByRiskLevel(riskLevel);
    }

    /** Look up a single case by surrogate ID. */
    @Transactional(readOnly = true)
    public Optional<FraudCase> getCaseById(Long id) {
        return fraudCaseRepository.findById(id);
    }

    // ── Review actions ────────────────────────────────────────────────────────

    /**
     * Update a fraud case status as a result of an analyst review action.
     *
     * @param id     The fraud case ID.
     * @param action Either "CONFIRM" (→ status REVIEWED) or "DISMISS" (→ status DISMISSED).
     * @return The updated case, or empty if the id was not found.
     */
    public Optional<FraudCase> reviewCase(Long id, String action) {
        String newStatus = "CONFIRM".equalsIgnoreCase(action) ? "REVIEWED" : "DISMISSED";
        int updated = fraudCaseRepository.updateStatus(id, newStatus);
        if (updated == 0) {
            log.warn("reviewCase: no fraud_case found for id={}", id);
            return Optional.empty();
        }
        log.info("reviewCase: id={} → status={}", id, newStatus);
        return fraudCaseRepository.findById(id);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Serialise the fraud score's reason list and degraded-mode flag to a JSON
     * string suitable for storage in the {@code fraud_cases.details} JSONB column.
     *
     * Uses a plain Map rather than a dedicated DTO to keep the details payload
     * self-contained and not tied to any response shape.
     */
    private String buildDetailsJson(FraudScore score) {
        Map<String, Object> details = Map.of(
            "reasons",      score.reasons(),
            "degradedMode", score.degradedMode()
        );
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            // Should never happen with a plain Map — fall back to a safe literal.
            log.error("Failed to serialise fraud case details: {}", e.getMessage(), e);
            return "{\"error\":\"serialisation_failed\"}";
        }
    }
}
