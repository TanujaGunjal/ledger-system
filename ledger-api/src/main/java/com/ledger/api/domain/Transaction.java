package com.ledger.api.domain;

import java.time.Instant;
import java.util.UUID;

public record Transaction(
    Long id,
    UUID transactionRef,
    String idempotencyKey,
    String description,
    String status,          // PENDING, POSTED, FAILED
    Instant createdAt,
    Instant postedAt        // null until POSTED
) {}
