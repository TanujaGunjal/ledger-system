package com.ledger.api.domain;

import java.time.Instant;

/**
 * Immutable domain record matching the outbox_events table.
 * Rows are inserted by PostingExecutor in the exact same @Transactional
 * boundary as the ledger entries — if the transaction commits, the outbox
 * row is durable; if it rolls back, the outbox row disappears with it.
 * The polling publisher (OutboxPublisher) reads only published = false rows.
 */
public record OutboxEvent(
    Long id,
    String aggregateType,
    String aggregateId,       // used as the Kafka message key for per-aggregate ordering
    String eventType,
    String payload,           // JSON-serialized event body
    Instant createdAt,
    boolean published,
    Instant publishedAt
) {}
