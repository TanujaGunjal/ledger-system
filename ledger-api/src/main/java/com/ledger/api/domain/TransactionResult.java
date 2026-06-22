package com.ledger.api.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The response returned by POST /api/v1/transactions.
 * Returned identically on an idempotent retry — the caller cannot distinguish
 * a first posting from a retry, which is the correct behaviour.
 */
public record TransactionResult(
    Long id,
    UUID transactionRef,
    String status,
    String description,
    Instant createdAt,
    Instant postedAt,
    List<LedgerEntry> entries
) {
    public static TransactionResult from(Transaction txn, List<LedgerEntry> entries) {
        return new TransactionResult(
            txn.id(),
            txn.transactionRef(),
            txn.status(),
            txn.description(),
            txn.createdAt(),
            txn.postedAt(),
            entries
        );
    }
}
