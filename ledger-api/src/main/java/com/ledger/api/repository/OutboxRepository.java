package com.ledger.api.repository;

import com.ledger.api.domain.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Insert a new outbox event. Must be called inside the same @Transactional
     * boundary as the ledger posting writes — that's what gives the at-least-once
     * Kafka delivery guarantee without a distributed transaction.
     *
     * @param aggregateType  e.g. "Transaction"
     * @param aggregateId    the transaction_ref UUID as a String — used as Kafka
     *                       message key to preserve per-transaction ordering.
     * @param eventType      e.g. "TransactionPosted"
     * @param payload        JSON-serialized event body
     * @return the persisted OutboxEvent with its generated id
     */
    public OutboxEvent insert(String aggregateType, String aggregateId,
                              String eventType, String payload) {
        String sql = """
            INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?::jsonb)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, aggregateType);
            ps.setString(2, aggregateId);
            ps.setString(3, eventType);
            ps.setString(4, payload);
            return ps;
        }, keyHolder);

        Long id = keyHolder.getKey().longValue();
        return findById(id);
    }

    /**
     * Claim up to {@code limit} unpublished rows with SELECT ... FOR UPDATE SKIP LOCKED.
     *
     * SKIP LOCKED is what makes this safe to run from multiple OutboxPublisher
     * instances concurrently (e.g. multiple replicas of ledger-api). Each
     * instance sees only the rows not already locked by a peer — there is no
     * risk of double-publishing from two instances racing on the same row.
     *
     * Rows are ordered by id ASC so that events are published in insertion order,
     * preserving causality for consumers that care about ordering.
     */
    public List<OutboxEvent> findUnpublished(int limit) {
        String sql = """
            SELECT id, aggregate_type, aggregate_id, event_type,
                   payload::text, created_at, published, published_at
            FROM   outbox_events
            WHERE  published = false
            ORDER  BY id ASC
            LIMIT  ?
            FOR UPDATE SKIP LOCKED
            """;
        return jdbcTemplate.query(
            sql,
            ps -> ps.setInt(1, limit),
            (rs, rowNum) -> new OutboxEvent(
                rs.getLong("id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("published"),
                rs.getTimestamp("published_at") != null
                    ? rs.getTimestamp("published_at").toInstant() : null
            )
        );
    }

    /**
     * Mark a single outbox row as published. Only called after the Kafka broker
     * has acknowledged the message — never before. A crash between send and
     * markPublished means the row stays unpublished and will be resent on
     * restart, giving at-least-once delivery. Kafka consumers must be idempotent.
     */
    public void markPublished(Long id) {
        jdbcTemplate.update(
            "UPDATE outbox_events SET published = true, published_at = now() WHERE id = ?",
            id
        );
    }

    private OutboxEvent findById(Long id) {
        return jdbcTemplate.queryForObject(
            """
            SELECT id, aggregate_type, aggregate_id, event_type,
                   payload::text, created_at, published, published_at
            FROM   outbox_events WHERE id = ?
            """,
            (rs, rowNum) -> new OutboxEvent(
                rs.getLong("id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("published"),
                rs.getTimestamp("published_at") != null
                    ? rs.getTimestamp("published_at").toInstant() : null
            ),
            id
        );
    }
}
