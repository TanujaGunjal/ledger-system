package com.ledger.api.repository;

import com.ledger.api.domain.AccountBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AccountBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * SELECT ... FOR UPDATE on the account_balances row for this account.
     *
     * This is the pessimistic write lock that serialises concurrent postings
     * for the same account. Once this lock is acquired, no other transaction
     * can read-then-update this row until the current transaction commits or
     * rolls back — eliminating the lost-update race condition.
     *
     * CALLER RESPONSIBILITY: invoke this method for each account in ascending
     * account_id order. The ordering is enforced in LedgerService, not here.
     * Keeping the lock acquisition in one place (selectForUpdate) and the
     * ordering in another (LedgerService) makes both easy to audit.
     */
    public AccountBalance selectForUpdate(Long accountId) {
        return jdbcTemplate.query(
            "SELECT account_id, balance, last_entry_sequence, updated_at " +
            "FROM account_balances WHERE account_id = ? FOR UPDATE",
            ps -> ps.setLong(1, accountId),
            rs -> {
                if (rs.next()) {
                    return new AccountBalance(
                        rs.getLong("account_id"),
                        rs.getBigDecimal("balance"),
                        rs.getLong("last_entry_sequence"),
                        rs.getTimestamp("updated_at").toInstant()
                    );
                }
                throw new IllegalStateException(
                    "No account_balances row for account_id=" + accountId +
                    ". Every account must have a balance row — was it created via AccountRepository?"
                );
            }
        );
    }

    /**
     * Write the updated balance and last_entry_sequence back to the DB.
     * Always called inside the same @Transactional boundary as selectForUpdate,
     * so the FOR UPDATE lock is still held when this runs.
     */
    public void update(Long accountId, java.math.BigDecimal newBalance, Long newLastEntrySequence) {
        jdbcTemplate.update(
            "UPDATE account_balances SET balance = ?, last_entry_sequence = ?, updated_at = now() " +
            "WHERE account_id = ?",
            newBalance, newLastEntrySequence, accountId
        );
    }

    public Optional<AccountBalance> findByAccountId(Long accountId) {
        return jdbcTemplate.query(
            "SELECT account_id, balance, last_entry_sequence, updated_at " +
            "FROM account_balances WHERE account_id = ?",
            ps -> ps.setLong(1, accountId),
            rs -> {
                if (rs.next()) {
                    return Optional.of(new AccountBalance(
                        rs.getLong("account_id"),
                        rs.getBigDecimal("balance"),
                        rs.getLong("last_entry_sequence"),
                        rs.getTimestamp("updated_at").toInstant()
                    ));
                }
                return Optional.empty();
            }
        );
    }
}
