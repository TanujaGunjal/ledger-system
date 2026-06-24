package com.ledger.fraud.controller;

import com.ledger.fraud.domain.FraudCase;
import com.ledger.fraud.domain.FraudScore;
import com.ledger.fraud.service.FraudCaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller exposing fraud detection and case management endpoints.
 *
 * Endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/fraud/score} — synchronous pre-posting fraud check.
 *       Called by ledger-api before committing a transaction to the ledger.</li>
 *   <li>{@code GET  /api/v1/fraud/cases?status=} — analyst queue with optional filter.</li>
 *   <li>{@code GET  /api/v1/fraud/cases?riskLevel=} — filter by HIGH/MEDIUM/LOW.</li>
 *   <li>{@code POST /api/v1/fraud/cases/{id}/review} — confirm or dismiss a case.</li>
 *   <li>{@code GET  /api/v1/fraud/health} — lightweight liveness probe.</li>
 * </ul>
 *
 * The score endpoint never returns an error status due to fraud-service internals —
 * all exceptions inside the scoring engine are caught and surfaced as LOW risk
 * (degraded mode). This ensures a fraud-service outage never blocks a transaction.
 */
@RestController
@RequestMapping("/api/v1/fraud")
public class FraudController {

    private static final Logger log = LoggerFactory.getLogger(FraudController.class);

    private final FraudCaseService fraudCaseService;

    public FraudController(FraudCaseService fraudCaseService) {
        this.fraudCaseService = fraudCaseService;
    }

    // ── POST /api/v1/fraud/score ──────────────────────────────────────────────

    /**
     * Synchronous fraud scoring endpoint — used by ledger-api as a pre-posting check.
     *
     * The caller provides transaction context; this endpoint scores it and returns
     * the result. Cases are persisted for MEDIUM and HIGH risk only.
     *
     * Request body shape:
     * <pre>
     * {
     *   "transactionRef": "uuid",
     *   "accountId": 123,
     *   "amount": 500.00,
     *   "currency": "USD",
     *   "entryType": "DEBIT"
     * }
     * </pre>
     *
     * This endpoint always returns 200 OK — never 500. If the engine fails internally,
     * the response will carry {@code "riskLevel": "LOW", "degradedMode": true} so
     * the caller can proceed with the transaction.
     */
    @PostMapping("/score")
    public ResponseEntity<Map<String, Object>> scoreTransaction(@RequestBody Map<String, Object> request) {

        String transactionRef = (String) request.get("transactionRef");
        Long   accountId      = toLong(request.get("accountId"));
        BigDecimal amount     = toBigDecimal(request.get("amount"));
        String currency       = (String) request.getOrDefault("currency", "USD");
        String entryType      = (String) request.getOrDefault("entryType", "DEBIT");

        log.debug("Score request: txnRef={} accountId={} amount={} entryType={}",
                  transactionRef, accountId, amount, entryType);

        // Only score DEBIT entries — CREDIT entries are not the initiating side
        // and do not need fraud evaluation (the corresponding DEBIT was already scored).
        if (!"DEBIT".equalsIgnoreCase(entryType)) {
            return ResponseEntity.ok(Map.of(
                "riskLevel",      "LOW",
                "score",          0,
                "triggeredRules", List.of(),
                "reasons",        List.of(),
                "recommendation", "ALLOW"
            ));
        }

        FraudScore score = fraudCaseService.scoreAndPersist(
            transactionRef != null ? transactionRef : "unknown",
            accountId      != null ? accountId      : 0L,
            amount         != null ? amount          : BigDecimal.ZERO,
            currency
        );

        return ResponseEntity.ok(Map.of(
            "riskLevel",      score.riskLevel().name(),
            "score",          score.totalScore(),
            "triggeredRules", score.triggeredRules(),
            "reasons",        score.reasons(),
            "recommendation", score.recommendation(),
            "degradedMode",   score.degradedMode()
        ));
    }

    // ── GET /api/v1/fraud/cases ───────────────────────────────────────────────

    /**
     * Fetch fraud cases with optional filter parameters.
     *
     * Supports two mutually exclusive query params:
     * <ul>
     *   <li>{@code ?status=OPEN|REVIEWED|DISMISSED|ALL} — filter by lifecycle status.
     *       Defaults to OPEN if neither param is provided.</li>
     *   <li>{@code ?riskLevel=HIGH|MEDIUM|LOW} — filter by risk classification.</li>
     * </ul>
     */
    @GetMapping("/cases")
    public ResponseEntity<List<FraudCase>> getCases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String riskLevel) {

        List<FraudCase> cases;

        if (riskLevel != null && !riskLevel.isBlank()) {
            cases = fraudCaseService.getCasesByRiskLevel(riskLevel);
        } else {
            // Default filter is OPEN — analysts see their work queue by default.
            String effectiveStatus = (status != null && !status.isBlank()) ? status : "OPEN";
            cases = fraudCaseService.getCasesByStatus(effectiveStatus);
        }

        return ResponseEntity.ok(cases);
    }

    // ── POST /api/v1/fraud/cases/{id}/review ─────────────────────────────────

    /**
     * Analyst action: confirm fraud (CONFIRM) or clear the case (DISMISS).
     *
     * Request body: {@code { "action": "CONFIRM" | "DISMISS", "note": "optional" }}
     *
     * Returns 200 with the updated case, or 404 if the id is not found.
     */
    @PostMapping("/cases/{id}/review")
    public ResponseEntity<?> reviewCase(@PathVariable Long id,
                                        @RequestBody Map<String, String> body) {

        String action = body.getOrDefault("action", "");
        if (!action.equalsIgnoreCase("CONFIRM") && !action.equalsIgnoreCase("DISMISS")) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "action must be CONFIRM or DISMISS"));
        }

        Optional<FraudCase> updated = fraudCaseService.reviewCase(id, action);
        return updated
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Fraud case not found: " + id)));
    }

    // ── GET /api/v1/fraud/health ──────────────────────────────────────────────

    /**
     * Lightweight health endpoint used by docker-compose healthchecks and the
     * frontend to verify the service is reachable before displaying the Fraud Queue tab.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status",  "UP",
            "service", "fraud-service"
        ));
    }

    // ── Type conversion helpers ───────────────────────────────────────────────

    /** Jackson deserialises numbers from JSON as Integer or Double — coerce to Long. */
    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Double d) return d.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Coerce a JSON-deserialised number to BigDecimal. */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
