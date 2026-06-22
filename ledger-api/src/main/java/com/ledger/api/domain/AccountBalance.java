package com.ledger.api.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * This is NOT the source of truth -- it's a materialized view derived from
 * ledger_entries, kept in sync inside the same DB transaction as every posting.
 * If it's ever in doubt, the truth is: sum of ledger_entries for the account.
 */
public record AccountBalance(
    Long accountId,
    BigDecimal balance,
    Long lastEntrySequence,
    Instant updatedAt
) {}
