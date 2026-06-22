package com.ledger.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row from reconciliation_matches — a successfully matched internal/external pair.
 * Presence of a row here is the definition of "reconciled" for both sides.
 */
public record ReconciliationMatch(
        Long id,
        String transactionRef,
        String externalReference,
        BigDecimal internalAmount,
        BigDecimal externalAmount,
        BigDecimal amountDelta,     // |internal - external|, always >= 0
        int dateDeltaDays,          // |posted_at - statement_date| in days, informational
        Instant matchedAt
) {}
