package com.ledger.api.repository;

import com.ledger.api.domain.Account;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Phase 0 scope only: create an account, seed its zero balance, read it back.
 * The interesting part -- SELECT ... FOR UPDATE, deterministic lock ordering,
 * posting entries -- lands here in Phase 1. Keeping it out for now so we can
 * verify the wiring (Spring Boot -> Postgres -> Flyway) actually works first.
 */
@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Account createAccount(String accountNumber, String ownerName, String accountType, String currency) {
        String insertAccountSql = """
            INSERT INTO accounts (account_number, owner_name, account_type, currency, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertAccountSql, new String[]{"id"});
            ps.setString(1, accountNumber);
            ps.setString(2, ownerName);
            ps.setString(3, accountType);
            ps.setString(4, currency);
            return ps;
        }, keyHolder);

        Long id = keyHolder.getKey().longValue();

        // Every account gets a zero-balance row created in lockstep. The posting
        // algorithm in Phase 1 will assume this row always exists.
        jdbcTemplate.update(
            "INSERT INTO account_balances (account_id, balance, last_entry_sequence) VALUES (?, 0, 0)",
            id
        );

        return findById(id).orElseThrow();
    }

    /**
     * Return all ACTIVE accounts ordered by id ascending.
     * Used by the frontend account-selector dropdown. No pagination —
     * portfolio scale; will need a cursor once account count grows past ~1000.
     */
    public List<Account> findAll() {
        return jdbcTemplate.query(
            "SELECT id, account_number, owner_name, account_type, currency, status, created_at " +
            "FROM accounts WHERE status = 'ACTIVE' ORDER BY id ASC",
            (rs, row) -> new Account(
                rs.getLong("id"),
                rs.getString("account_number"),
                rs.getString("owner_name"),
                rs.getString("account_type"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
    }

    public Optional<Account> findById(Long id) {
        String sql = """
            SELECT id, account_number, owner_name, account_type, currency, status, created_at
            FROM accounts WHERE id = ?
            """;

        return jdbcTemplate.query(
            sql,
            ps -> ps.setLong(1, id),
            rs -> {
                if (rs.next()) {
                    return Optional.of(new Account(
                        rs.getLong("id"),
                        rs.getString("account_number"),
                        rs.getString("owner_name"),
                        rs.getString("account_type"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return Optional.empty();
            }
        );
    }
}
