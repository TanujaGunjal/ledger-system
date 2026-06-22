package com.ledger.reconciliation.repository;

import com.ledger.reconciliation.domain.UnmatchedTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read-only access to ledger-api's core tables (transactions + ledger_entries).
 * The reconciliation-worker never writes to these tables.
 */
@Repository
public class InternalLedgerRepository {

    private final JdbcTemplate jdbc;

    public InternalLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find POSTED transactions that:
     *   (a) are not already in reconciliation_matches, AND
     *   (b) were posted more than {@code gracePeriodHours} hours ago.
     *
     * Transactions with an existing OPEN exception ARE included — the external
     * entry may have arrived since the last run, allowing self-resolution.
     *
     * NOTE: SUM(le.amount) assumes single-currency transactions. If mixed-currency
     * postings are ever supported, this must be grouped by currency and compared
     * against a per-currency external amount.
     */
    public List<UnmatchedTransaction> findUnmatchedPosted(int gracePeriodHours) {
        String sql = """
            SELECT t.transaction_ref::text          AS transaction_ref,
                   t.posted_at                      AS posted_at,
                   SUM(le.amount)                   AS internal_debit_sum,
                   MIN(le.currency)                 AS currency
            FROM   transactions t
            JOIN   ledger_entries le
                   ON  le.transaction_id = t.id
                   AND le.entry_type     = 'DEBIT'
            WHERE  t.status    = 'POSTED'
            AND    t.posted_at < now() - (? || ' hours')::INTERVAL
            AND    NOT EXISTS (
                       SELECT 1 FROM reconciliation_matches rm
                       WHERE  rm.transaction_ref = t.transaction_ref::text)
            GROUP  BY t.transaction_ref, t.posted_at
            ORDER  BY t.posted_at ASC
            """;
        return jdbc.query(sql,
                ps -> ps.setInt(1, gracePeriodHours),
                (rs, i) -> new UnmatchedTransaction(
                        rs.getString("transaction_ref"),
                        rs.getTimestamp("posted_at").toInstant(),
                        rs.getBigDecimal("internal_debit_sum"),
                        rs.getString("currency")
                ));
    }

    /**
     * Find POSTED transactions that have no external_statement_entries row yet.
     * Used by MockFeedGenerator to decide which transactions need a synthetic entry.
     */
    public List<UnmatchedTransaction> findPostedWithNoExternalEntry() {
        String sql = """
            SELECT t.transaction_ref::text          AS transaction_ref,
                   t.posted_at                      AS posted_at,
                   SUM(le.amount)                   AS internal_debit_sum,
                   MIN(le.currency)                 AS currency
            FROM   transactions t
            JOIN   ledger_entries le
                   ON  le.transaction_id = t.id
                   AND le.entry_type     = 'DEBIT'
            WHERE  t.status = 'POSTED'
            AND    NOT EXISTS (
                       SELECT 1 FROM external_statement_entries ese
                       WHERE  ese.external_reference = t.transaction_ref::text)
            GROUP  BY t.transaction_ref, t.posted_at
            ORDER  BY t.posted_at ASC
            LIMIT  100
            """;
        return jdbc.query(sql,
                (rs, i) -> new UnmatchedTransaction(
                        rs.getString("transaction_ref"),
                        rs.getTimestamp("posted_at").toInstant(),
                        rs.getBigDecimal("internal_debit_sum"),
                        rs.getString("currency")
                ));
    }
}
