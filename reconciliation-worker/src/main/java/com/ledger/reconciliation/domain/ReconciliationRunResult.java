package com.ledger.reconciliation.domain;

/**
 * Summary of a single reconciliation engine run.
 *
 * matched        — transactions successfully matched to an external entry.
 * resolved       — OPEN exceptions auto-resolved because a match was found
 *                  (e.g. external entry arrived late, fixing a prior MISSING_EXTERNAL).
 * missingExternal — transactions with no external entry after the grace period.
 * missingInternal — external entries with no corresponding internal transaction.
 * amountMismatch  — transactions where the external amount differs beyond tolerance.
 */
public record ReconciliationRunResult(
        int matched,
        int resolved,
        int missingExternal,
        int missingInternal,
        int amountMismatch
) {}
