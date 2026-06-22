package com.ledger.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A POSTED internal transaction that has not yet been matched against an external
 * statement entry. Used as the unit of work in ReconciliationEngine Pass 1.
 *
 * internalDebitSum: SUM of all DEBIT ledger_entries for this transaction.
 * NOTE: assumes single-currency transactions. If mixed-currency postings are
 * ever supported, this sum must be grouped by currency and compared against a
 * per-currency external amount.
 *
 * currency: taken from MIN(ledger_entries.currency) — consistent for single-currency
 * transactions; the first currency alphabetically for mixed (future concern only).
 */
public record UnmatchedTransaction(
        String transactionRef,
        Instant postedAt,
        BigDecimal internalDebitSum,
        String currency
) {}
