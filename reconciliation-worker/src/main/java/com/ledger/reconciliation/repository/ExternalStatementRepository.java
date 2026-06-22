package com.ledger.reconciliation.repository;

import com.ledger.reconciliation.domain.ExternalStatementEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ExternalStatementRepository {

    private final JdbcTemplate jdbc;

    public ExternalStatementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Look up an external statement entry by its external_reference.
     * Used in Pass 1 of the reconciliation engine to find the counterpart
     * for a given internal transaction.
     */
    public Optional<ExternalStatementEntry> findByExternalReference(String externalReference) {
        List<ExternalStatementEntry> rows = jdbc.query(
                """
                SELECT id, external_reference, amount, currency,
                       statement_date, raw_payload::text, ingested_at
                FROM   external_statement_entries
                WHERE  external_reference = ?
                """,
                ps -> ps.setString(1, externalReference),
                (rs, i) -> map(rs));
        return rows.stream().findFirst();
    }

    /**
     * Find external entries that have no matching internal transaction AND are
     * not already recorded in reconciliation_matches. These are candidates for
     * MISSING_INTERNAL exceptions (Pass 2 of the engine).
     */
    public List<ExternalStatementEntry> findUnmatchedWithNoInternalTransaction() {
        return jdbc.query(
                """
                SELECT ese.id, ese.external_reference, ese.amount, ese.currency,
                       ese.statement_date, ese.raw_payload::text, ese.ingested_at
                FROM   external_statement_entries ese
                WHERE  NOT EXISTS (
                           SELECT 1 FROM reconciliation_matches rm
                           WHERE  rm.external_reference = ese.external_reference)
                AND    NOT EXISTS (
                           SELECT 1 FROM transactions t
                           WHERE  t.transaction_ref::text = ese.external_reference)
                """,
                (rs, i) -> map(rs));
    }

    /**
     * Insert an external statement entry. Silently ignores conflicts on
     * external_reference (ON CONFLICT DO NOTHING) so the mock generator
     * is idempotent on repeated runs.
     */
    public void insertIfAbsent(String externalReference, BigDecimal amount,
                               String currency, LocalDate statementDate,
                               String rawPayloadJson) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO external_statement_entries
                        (external_reference, amount, currency, statement_date, raw_payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (external_reference) DO NOTHING
                    """);
            ps.setString(1, externalReference);
            ps.setBigDecimal(2, amount);
            ps.setString(3, currency);
            ps.setDate(4, Date.valueOf(statementDate));
            ps.setString(5, rawPayloadJson);
            return ps;
        });
    }

    /** Count how many external entries currently exist with no matching internal transaction. */
    public int countOrphans() {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM external_statement_entries ese
                WHERE NOT EXISTS (
                    SELECT 1 FROM transactions t
                    WHERE t.transaction_ref::text = ese.external_reference)
                """,
                Integer.class);
        return n == null ? 0 : n;
    }

    private ExternalStatementEntry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExternalStatementEntry(
                rs.getLong("id"),
                rs.getString("external_reference"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getDate("statement_date").toLocalDate(),
                rs.getString("raw_payload"),
                rs.getTimestamp("ingested_at").toInstant()
        );
    }
}
