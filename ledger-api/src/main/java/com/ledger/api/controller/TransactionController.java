package com.ledger.api.controller;

import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.TransactionResult;
import com.ledger.api.exception.MissingIdempotencyKeyException;
import com.ledger.api.service.FraudServiceClient;
import com.ledger.api.service.FraudServiceClient.FraudCheckResult;
import com.ledger.api.service.LedgerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final LedgerService      ledgerService;
    private final FraudServiceClient fraudServiceClient;

    public TransactionController(LedgerService ledgerService,
                                 FraudServiceClient fraudServiceClient) {
        this.ledgerService      = ledgerService;
        this.fraudServiceClient = fraudServiceClient;
    }

    /**
     * POST /api/v1/transactions
     *
     * Required header: Idempotency-Key (a UUID generated client-side before the
     * first attempt — re-sending the same key on retry returns the original result
     * without re-posting the transaction).
     *
     * Pre-posting fraud gate (added in Phase 3):
     *   - Calls fraud-service with a 500 ms timeout before committing to the ledger.
     *   - HIGH risk  → returns HTTP 403 with body {"blocked": true, "reason": "...", "score": N}.
     *                  Transaction is NOT posted.
     *   - MEDIUM risk → proceeds but adds response headers X-Fraud-Risk and X-Fraud-Score.
     *   - LOW risk   → proceeds normally, no extra headers.
     *   - Unreachable → proceeds normally (degraded mode). Never blocks on service failure.
     *
     * Returns 200 on idempotent retry (result already exists).
     * Returns 201 on first successful posting.
     * Returns 400 if entries don't net to zero or if the header is missing.
     * Returns 403 if fraud-service classifies the transaction as HIGH risk.
     * Returns 409 if a DEBIT would overdraw an account.
     */
    @PostMapping
    public ResponseEntity<?> postTransaction(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PostingRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        // ── Pre-posting fraud check ────────────────────────────────────────────
        // We generate a provisional correlation UUID here rather than reusing the
        // idempotency key. The real transactionRef is assigned inside PostingExecutor
        // (after the DB INSERT) so it is not available yet. The fraud-service stores
        // this provisional ref in fraud_cases; the idempotency guard in FraudCaseService
        // deduplicates any later Kafka-driven re-score for the same case.
        String provisionalRef = UUID.randomUUID().toString();
        FraudCheckResult fraudResult = fraudServiceClient.checkFraud(provisionalRef, request);

        if (fraudResult.isHighRisk()) {
            log.warn("Transaction BLOCKED by fraud-service — provisionalRef={} score={}",
                     provisionalRef, fraudResult.score());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "blocked", true,
                "reason",  "Transaction blocked by fraud detection",
                "score",   fraudResult.score(),
                "ref",     provisionalRef
            ));
        }

        // ── Post the transaction ───────────────────────────────────────────────
        TransactionResult result = ledgerService.postTransaction(request, idempotencyKey.trim());

        // 200 on idempotent replay (status was already POSTED before we ran),
        // 201 on a fresh successful posting. The caller can distinguish these
        // via HTTP status if needed, but both bodies are identical by design.
        HttpStatus status = "POSTED".equals(result.status()) ? HttpStatus.CREATED : HttpStatus.OK;

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);

        // Attach risk headers for MEDIUM risk so downstream systems (API gateways,
        // audit pipelines) can react without calling fraud-service themselves.
        if (fraudResult.isMediumRisk()) {
            builder = builder.header("X-Fraud-Risk",  "MEDIUM")
                             .header("X-Fraud-Score", String.valueOf(fraudResult.score()));
            log.info("Transaction posted with MEDIUM fraud risk — txnRef={} score={}",
                     result.transactionRef(), fraudResult.score());
        }

        return builder.body(result);
    }
}
