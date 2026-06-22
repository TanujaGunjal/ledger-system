package com.ledger.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row from external_statement_entries — a single item from the bank/processor
 * statement feed. In production this is populated by a real ingestion job.
 * In Phase 3 it is populated by MockFeedGenerator.
 */
public record ExternalStatementEntry(
        Long id,
        String externalReference,
        BigDecimal amount,
        String currency,
        LocalDate statementDate,
        String rawPayload,    // raw JSON preserved for audit; may be null
        Instant ingestedAt
) {}
