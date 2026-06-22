package com.ledger.reconciliation.controller;

import com.ledger.reconciliation.domain.ReconciliationException;
import com.ledger.reconciliation.domain.ReconciliationRunResult;
import com.ledger.reconciliation.repository.ReconciliationExceptionRepository;
import com.ledger.reconciliation.service.ReconciliationEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestParam;

/**
 * REST API for the reconciliation-worker (port 9091).
 *
 * Endpoints:
 *   GET  /api/v1/reconciliation/exceptions           — OPEN exceptions only (default queue view)
 *   GET  /api/v1/reconciliation/exceptions?status=ALL      — all exceptions (OPEN + RESOLVED)
 *   GET  /api/v1/reconciliation/exceptions?status=RESOLVED — resolved exceptions only
 *   POST /api/v1/reconciliation/run          — trigger a reconciliation run immediately (for ops/debug)
 *   GET  /api/v1/reconciliation/health       — liveness check
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationExceptionRepository exceptionRepo;
    private final ReconciliationEngine              engine;

    public ReconciliationController(ReconciliationExceptionRepository exceptionRepo,
                                    ReconciliationEngine engine) {
        this.exceptionRepo = exceptionRepo;
        this.engine        = engine;
    }

    /**
     * List reconciliation exceptions.
     *
     * ?status= param (optional, case-sensitive):
     *   omitted or "OPEN"     — returns OPEN exceptions only (primary queue view, default)
     *   "ALL"                 — returns all exceptions regardless of status
     *   "RESOLVED"            — returns resolved exceptions only
     *   anything else         — 400 Bad Request
     */
    @GetMapping("/exceptions")
    public ResponseEntity<?> listExceptions(
            @RequestParam(name = "status", required = false, defaultValue = "OPEN") String status) {
        return switch (status) {
            case "OPEN"     -> ResponseEntity.ok(exceptionRepo.findAllOpen());
            case "ALL"      -> ResponseEntity.ok(exceptionRepo.findAll());
            case "RESOLVED" -> ResponseEntity.ok(exceptionRepo.findByStatus("RESOLVED"));
            default         -> ResponseEntity.badRequest()
                                   .body("Invalid status param '" + status +
                                         "'. Accepted values: OPEN, ALL, RESOLVED");
        };
    }

    /**
     * Trigger an immediate reconciliation run.
     * POST because a reconciliation pass writes rows (reconciliation_matches INSERTs and
     * reconciliation_exceptions UPDATEs) — it is neither safe nor idempotent, so GET
     * is incorrect per RFC 9110 §9.2.1.
     * Intended for ops tooling and manual testing — not for regular clients.
     * In production this would be behind an admin auth guard.
     */
    @PostMapping("/run")
    public ResponseEntity<ReconciliationRunResult> triggerRun() {
        ReconciliationRunResult result = engine.reconcileOnce();
        return ResponseEntity.ok(result);
    }

    /** Liveness check. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "reconciliation-worker");
    }
}
