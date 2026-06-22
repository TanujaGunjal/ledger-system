package com.ledger.api.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The body for POST /api/v1/transactions.
 * The caller must supply an Idempotency-Key header (validated in the controller).
 */
public record PostingRequest(
    String description,

    @NotNull @Size(min = 2, message = "A posting requires at least two entries (one debit, one credit)")
    List<PostingEntry> entries
) {
    /**
     * One leg of a double-entry posting.
     */
    public record PostingEntry(
        @NotNull Long accountId,

        @NotNull EntryType entryType,

        @NotNull @DecimalMin(value = "0.0001", message = "Amount must be positive")
        BigDecimal amount,

        @NotBlank String currency
    ) {}

    public enum EntryType {
        DEBIT, CREDIT
    }
}
