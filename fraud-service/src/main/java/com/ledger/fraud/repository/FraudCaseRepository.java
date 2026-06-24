package com.ledger.fraud.repository;

import com.ledger.fraud.domain.FraudCase;
import com.ledger.fraud.domain.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Plain-JDBC data access layer for the {@code fraud_cases} table.
 *
 * Follows the same JdbcTemplate patterns as {@code AccountBalanceRepository}
 * and other repositories in ledger-api: constructor-injected JdbcTemplate,
 * inline SQL strings, lambda-based RowMapper, and no JPA annotations anywhere.
 *
 * The {@link #existsByTransactionRef(String)} method is the key idempotency guard:
 * the Kafka consumer calls it before inserting a new case so that Kafka redeliveries
 * on consumer restart do not create duplicate fraud cases for the same transaction.
 */
@Repository
public class FraudCaseRepository {

    private static final Logger log = LoggerFactory.getLogger(FraudCaseRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public FraudCaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    /**
     * Maps a {@code fraud_cases} result row to a {@link FraudCase} record.
     * Handles nullable columns (amount, currency, details, reviewed_at) defensively.
     */
    private static final RowMapper<FraudCase> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp reviewedTs = rs.getTimestamp("reviewed_at");
        return new FraudCase(
            rs.getLong("id"),
            rs.getString("transaction_ref"),
            rs.getLong("account_id"),
            RiskLevel.valueOf(rs.getString("risk_level")),
            rs.getInt("score"),
            rs.getString("triggered_rules"),
            rs.getString("status"),
            rs.getBigDecimal("amount"),            // nullable
            rs.getString("currency"),              // nullable
            rs.getString("details"),               // nullable
            rs.getTimestamp("created_at").toInstant(),
            reviewedTs != null ? reviewedTs.toInstant() : null
        );
    };

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Idempotency guard: returns true if a fraud_case row already exists for
     * the given transaction_ref. Used by the Kafka consumer to avoid duplicates
     * on message redelivery.
     */
    public boolean existsByTransactionRef(String transactionRef) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fraud_cases WHERE transaction_ref = ?",
            Integer.class,
            transactionRef
        );
        return count != null && count > 0;
    }

    /**
     * Fetch cases filtered by status. Passing "ALL" returns every case.
     * Results are ordered newest-first so the analyst queue shows the most
     * recent activity at the top.
     */
    public List<FraudCase> findByStatus(String status) {
        if ("ALL".equalsIgnoreCase(status)) {
            return jdbcTemplate.query(
                "SELECT * FROM fraud_cases ORDER BY created_at DESC",
                ROW_MAPPER
            );
        }
        return jdbcTemplate.query(
            "SELECT * FROM fraud_cases WHERE status = ? ORDER BY created_at DESC",
            ROW_MAPPER,
            status
        );
    }

    /**
     * Fetch cases filtered by risk level. Case-insensitive on the caller side
     * (the controller normalises to uppercase before calling here).
     */
    public List<FraudCase> findByRiskLevel(String riskLevel) {
        return jdbcTemplate.query(
            "SELECT * FROM fraud_cases WHERE risk_level = ? ORDER BY created_at DESC",
            ROW_MAPPER,
            riskLevel.toUpperCase()
        );
    }

    /** Look up a single case by its surrogate primary key. */
    public Optional<FraudCase> findById(Long id) {
        return jdbcTemplate.query(
            "SELECT * FROM fraud_cases WHERE id = ?",
            ps -> ps.setLong(1, id),
            rs -> {
                if (rs.next()) {
                    return Optional.of(ROW_MAPPER.mapRow(rs, 0));
                }
                return Optional.empty();
            }
        );
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Insert a new fraud case. Returns the auto-generated id.
     *
     * {@code triggeredRules} is stored as a comma-separated string of rule names.
     * {@code details} is a JSON string (may be null).
     */
    public long insert(String transactionRef,
                       Long accountId,
                       RiskLevel riskLevel,
                       int score,
                       String triggeredRules,
                       BigDecimal amount,
                       String currency,
                       String details) {

        // Use queryForObject with RETURNING id — simpler than KeyHolder for a
        // single-column BIGSERIAL primary key on Postgres.
        Long id = jdbcTemplate.queryForObject(
            """
            INSERT INTO fraud_cases
                (transaction_ref, account_id, risk_level, score, triggered_rules,
                 status, amount, currency, details)
            VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?::jsonb)
            RETURNING id
            """,
            Long.class,
            transactionRef, accountId, riskLevel.name(), score, triggeredRules,
            amount, currency, details
        );

        log.debug("Inserted fraud_case id={} txnRef={} riskLevel={} score={}",
                  id, transactionRef, riskLevel, score);
        return id != null ? id : -1L;
    }

    /**
     * Update the status and reviewed_at timestamp of a fraud case.
     * Called by the analyst review endpoint (REVIEWED or DISMISSED).
     *
     * @return number of rows updated (0 if the id was not found).
     */
    public int updateStatus(Long id, String newStatus) {
        int rows = jdbcTemplate.update(
            "UPDATE fraud_cases SET status = ?, reviewed_at = now() WHERE id = ?",
            newStatus, id
        );
        log.debug("Updated fraud_case id={} → status={} (rows={})", id, newStatus, rows);
        return rows;
    }
}
