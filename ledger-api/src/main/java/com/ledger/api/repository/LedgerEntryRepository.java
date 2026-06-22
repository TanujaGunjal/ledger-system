package com.ledger.api.repository;

import com.ledger.api.domain.LedgerEntry;
import com.ledger.api.domain.PostingRequest.EntryType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class LedgerEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    public LedgerEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Insert one immutable ledger entry.
     *
     * This table is INSERT-ONLY by design. There is no update or delete path
     * anywhere in the application — the sequence_no + (account_id, sequence_no)
     * unique constraint enforces that. Every financial event is permanently
     * recorded; corrections are made by posting new reversing entries.
     */
    public LedgerEntry insert(Long transactionId, Long accountId, EntryType entryType,
                              BigDecimal amount, String currency, Long sequenceNo) {
        String sql = """
            INSERT INTO ledger_entries
                (transaction_id, account_id, entry_type, amount, currency, sequence_no)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, transactionId);
            ps.setLong(2, accountId);
            ps.setString(3, entryType.name());
            ps.setBigDecimal(4, amount);
            ps.setString(5, currency);
            ps.setLong(6, sequenceNo);
            return ps;
        }, keyHolder);

        Long id = keyHolder.getKey().longValue();
        return findById(id);
    }

    private LedgerEntry findById(Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT id, transaction_id, account_id, entry_type, amount, currency, sequence_no, created_at " +
            "FROM ledger_entries WHERE id = ?",
            (rs, row) -> new LedgerEntry(
                rs.getLong("id"),
                rs.getLong("transaction_id"),
                rs.getLong("account_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getLong("sequence_no"),
                rs.getTimestamp("created_at").toInstant()
            ),
            id
        );
    }

    /**
     * Fetch all ledger entries for an account, ordered by sequence_no ascending.
     * sequence_no is a monotonic counter per account, so this is the canonical
     * chronological order of events for that account's ledger.
     *
     * @param afterSequence  cursor for pagination — pass 0 to start from the beginning.
     * @param limit          max rows to return.
     */
    public List<LedgerEntry> findByAccountId(Long accountId, Long afterSequence, int limit) {
        return jdbcTemplate.query(
            "SELECT id, transaction_id, account_id, entry_type, amount, currency, sequence_no, created_at " +
            "FROM ledger_entries " +
            "WHERE account_id = ? AND sequence_no > ? " +
            "ORDER BY sequence_no ASC " +
            "LIMIT ?",
            (rs, row) -> new LedgerEntry(
                rs.getLong("id"),
                rs.getLong("transaction_id"),
                rs.getLong("account_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getLong("sequence_no"),
                rs.getTimestamp("created_at").toInstant()
            ),
            accountId, afterSequence, limit
        );
    }

    public List<LedgerEntry> findByTransactionId(Long transactionId) {
        return jdbcTemplate.query(
            "SELECT id, transaction_id, account_id, entry_type, amount, currency, sequence_no, created_at " +
            "FROM ledger_entries WHERE transaction_id = ? ORDER BY id ASC",
            (rs, row) -> new LedgerEntry(
                rs.getLong("id"),
                rs.getLong("transaction_id"),
                rs.getLong("account_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getLong("sequence_no"),
                rs.getTimestamp("created_at").toInstant()
            ),
            transactionId
        );
    }
}
