package com.ledger.api.repository;

import com.ledger.api.domain.Transaction;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Insert a PENDING transaction row. The idempotency_key column has a UNIQUE
     * constraint, so a concurrent duplicate submission will throw DuplicateKeyException
     * here — the caller (LedgerService) catches that and returns the existing result.
     */
    public Transaction insertPending(String idempotencyKey, String description) {
        String sql = """
            INSERT INTO transactions (transaction_ref, idempotency_key, description, status)
            VALUES (?, ?, ?, 'PENDING')
            """;

        UUID ref = UUID.randomUUID();
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setObject(1, ref);
            ps.setString(2, idempotencyKey);
            ps.setString(3, description);
            return ps;
        }, keyHolder);

        Long id = keyHolder.getKey().longValue();
        return findById(id).orElseThrow();
    }

    /**
     * Mark a transaction POSTED and record the posted_at timestamp.
     * Called as the final step of postTransaction(), still inside the same
     * @Transactional boundary — commits together with the ledger entries.
     */
    public void markPosted(Long transactionId) {
        jdbcTemplate.update(
            "UPDATE transactions SET status = 'POSTED', posted_at = now() WHERE id = ?",
            transactionId
        );
    }

    public Optional<Transaction> findById(Long id) {
        return jdbcTemplate.query(
            "SELECT id, transaction_ref, idempotency_key, description, status, created_at, posted_at " +
            "FROM transactions WHERE id = ?",
            ps -> ps.setLong(1, id),
            rs -> {
                if (rs.next()) {
                    Timestamp postedAt = rs.getTimestamp("posted_at");
                    return Optional.of(new Transaction(
                        rs.getLong("id"),
                        rs.getObject("transaction_ref", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        postedAt != null ? postedAt.toInstant() : null
                    ));
                }
                return Optional.empty();
            }
        );
    }

    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query(
            "SELECT id, transaction_ref, idempotency_key, description, status, created_at, posted_at " +
            "FROM transactions WHERE idempotency_key = ?",
            ps -> ps.setString(1, idempotencyKey),
            rs -> {
                if (rs.next()) {
                    Timestamp postedAt = rs.getTimestamp("posted_at");
                    return Optional.of(new Transaction(
                        rs.getLong("id"),
                        rs.getObject("transaction_ref", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        postedAt != null ? postedAt.toInstant() : null
                    ));
                }
                return Optional.empty();
            }
        );
    }
}
