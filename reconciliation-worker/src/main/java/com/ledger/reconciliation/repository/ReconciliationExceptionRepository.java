package com.ledger.reconciliation.repository;

import com.ledger.reconciliation.domain.ExceptionType;
import com.ledger.reconciliation.domain.ReconciliationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;

@Repository
public class ReconciliationExceptionRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a new OPEN exception. Uses ON CONFLICT DO NOTHING backed by the
     * partial unique indexes from V2:
     *   (transaction_ref, exception_type) WHERE transaction_ref IS NOT NULL AND status='OPEN'
     *   (external_reference, exception_type) WHERE external_reference IS NOT NULL AND status='OPEN'
     *
     * Running the engine twice on the same unchanged data is therefore safe —
     * the second run inserts nothing.
     */
    public void insertIfAbsent(ExceptionType type,
                               String transactionRef,
                               String externalReference,
                               BigDecimal internalAmount,
                               BigDecimal externalAmount,
                               String detailsJson) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO reconciliation_exceptions
                        (exception_type, status, transaction_ref, external_reference,
                         internal_amount, external_amount, details)
                    VALUES (?, 'OPEN', ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT DO NOTHING
                    """);
            ps.setString(1, type.name());
            ps.setString(2, transactionRef);
            ps.setString(3, externalReference);
            if (internalAmount != null) ps.setBigDecimal(4, internalAmount);
            else                        ps.setNull(4, java.sql.Types.NUMERIC);
            if (externalAmount != null) ps.setBigDecimal(5, externalAmount);
            else                        ps.setNull(5, java.sql.Types.NUMERIC);
            ps.setString(6, detailsJson);
            return ps;
        });
    }

    /**
     * Resolve all OPEN exceptions for the given transactionRef in a single UPDATE.
     * Called in the same @Transactional boundary as the reconciliation_matches INSERT
     * so both changes are atomic — no observer can see a match without the resolution.
     *
     * Returns the number of rows updated (0 if no open exception existed — normal for
     * first-time matches, not an error condition).
     */
    public int resolveOpenByTransactionRef(String transactionRef) {
        return jdbc.update(
                """
                UPDATE reconciliation_exceptions
                SET    status      = 'RESOLVED',
                       resolved_at = now()
                WHERE  transaction_ref = ?
                AND    status          = 'OPEN'
                """,
                transactionRef);
    }

    /** Return all OPEN exceptions, ordered by creation time descending (newest first). */
    public List<ReconciliationException> findAllOpen() {
        return jdbc.query(
                """
                SELECT id, exception_type, status, transaction_ref, external_reference,
                       internal_amount, external_amount, details::text,
                       created_at, resolved_at
                FROM   reconciliation_exceptions
                WHERE  status = 'OPEN'
                ORDER  BY created_at DESC
                """,
                (rs, i) -> map(rs));
    }

    /**
     * Return ALL exceptions regardless of status, ordered by creation time descending.
     * Used by GET /exceptions?status=ALL.
     *
     * NOTE: No pagination yet. This will need LIMIT/OFFSET (or keyset pagination)
     * once the table grows past portfolio-scale data — not fixing now, flagging it
     * the same way mixed-currency and per-currency tolerance are flagged elsewhere.
     */
    public List<ReconciliationException> findAll() {
        return jdbc.query(
                """
                SELECT id, exception_type, status, transaction_ref, external_reference,
                       internal_amount, external_amount, details::text,
                       created_at, resolved_at
                FROM   reconciliation_exceptions
                ORDER  BY created_at DESC
                """,
                (rs, i) -> map(rs));
    }

    /** Return exceptions filtered to a single status value (e.g. 'RESOLVED'), newest first. */
    public List<ReconciliationException> findByStatus(String status) {
        return jdbc.query(
                """
                SELECT id, exception_type, status, transaction_ref, external_reference,
                       internal_amount, external_amount, details::text,
                       created_at, resolved_at
                FROM   reconciliation_exceptions
                WHERE  status = ?
                ORDER  BY created_at DESC
                """,
                ps -> ps.setString(1, status),
                (rs, i) -> map(rs));
    }

    /** Return all exceptions (any status) for a given transactionRef. Used in tests. */
    public List<ReconciliationException> findByTransactionRef(String transactionRef) {
        return jdbc.query(
                """
                SELECT id, exception_type, status, transaction_ref, external_reference,
                       internal_amount, external_amount, details::text,
                       created_at, resolved_at
                FROM   reconciliation_exceptions
                WHERE  transaction_ref = ?
                ORDER  BY created_at DESC
                """,
                ps -> ps.setString(1, transactionRef),
                (rs, i) -> map(rs));
    }

    private ReconciliationException map(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp resolvedTs = rs.getTimestamp("resolved_at");
        return new ReconciliationException(
                rs.getLong("id"),
                rs.getString("exception_type"),
                rs.getString("status"),
                rs.getString("transaction_ref"),
                rs.getString("external_reference"),
                rs.getBigDecimal("internal_amount"),
                rs.getBigDecimal("external_amount"),
                rs.getString("details"),
                rs.getTimestamp("created_at").toInstant(),
                resolvedTs != null ? resolvedTs.toInstant() : null
        );
    }
}
