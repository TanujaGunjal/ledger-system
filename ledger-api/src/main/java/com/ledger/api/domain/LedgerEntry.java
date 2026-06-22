package com.ledger.api.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntry(
    Long id,
    Long transactionId,
    Long accountId,
    String entryType,       // DEBIT or CREDIT
    BigDecimal amount,
    String currency,
    Long sequenceNo,
    Instant createdAt
) {}
