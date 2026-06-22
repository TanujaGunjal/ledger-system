package com.ledger.api.domain;

import java.time.Instant;

public record Account(
    Long id,
    String accountNumber,
    String ownerName,
    String accountType,
    String currency,
    String status,
    Instant createdAt
) {}
