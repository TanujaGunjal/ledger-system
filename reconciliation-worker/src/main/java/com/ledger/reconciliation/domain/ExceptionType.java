package com.ledger.reconciliation.domain;

/**
 * The three kinds of reconciliation discrepancy the engine can raise.
 *
 * MISSING_EXTERNAL  — an internal POSTED transaction has no matching external
 *                     statement entry after the configured grace period.
 *
 * MISSING_INTERNAL  — an external statement entry has no matching internal
 *                     transaction (bank reported a movement we have no record of).
 *
 * AMOUNT_MISMATCH   — both sides have the same external_reference but the amounts
 *                     differ by more than the configured tolerance. Both amounts
 *                     are recorded in the exception's details column.
 */
public enum ExceptionType {
    MISSING_EXTERNAL,
    MISSING_INTERNAL,
    AMOUNT_MISMATCH
}
