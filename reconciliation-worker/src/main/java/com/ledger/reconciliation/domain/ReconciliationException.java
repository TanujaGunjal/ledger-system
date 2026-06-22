package com.ledger.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row from reconciliation_exceptions — a discrepancy that needs review.
 *
 * status is either OPEN (needs action) or RESOLVED (a subsequent engine run
 * found a matching external entry and self-resolved the exception atomically).
 */
public record ReconciliationException(
        Long id,
        String exceptionType,
        String status,
        String transactionRef,      // null for MISSING_INTERNAL
        String externalReference,   // null for MISSING_EXTERNAL
        BigDecimal internalAmount,  // null for MISSING_INTERNAL
        BigDecimal externalAmount,  // null for MISSING_EXTERNAL
        String details,             // JSON string with delta, dates, tolerance, etc.
        Instant createdAt,
        Instant resolvedAt          // null while OPEN
) {}
