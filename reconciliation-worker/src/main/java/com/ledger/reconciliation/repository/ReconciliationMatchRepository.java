package com.ledger.reconciliation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;

@Repository
public class ReconciliationMatchRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationMatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a reconciliation match. The UNIQUE constraints on transaction_ref and
     * external_reference enforce that neither side can be matched twice. Duplicate
     * attempts will throw a DataIntegrityViolationException — callers should guard
     * against this by checking reconciliation_matches before calling (the engine
     * already does this via findUnmatchedPosted's NOT EXISTS clause).
     */
    public void insert(String transactionRef, String externalReference,
                       BigDecimal internalAmount, BigDecimal externalAmount,
                       BigDecimal amountDelta, int dateDeltaDays) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO reconciliation_matches
                        (transaction_ref, external_reference,
                         internal_amount, external_amount, amount_delta, date_delta_days)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, transactionRef);
            ps.setString(2, externalReference);
            ps.setBigDecimal(3, internalAmount);
            ps.setBigDecimal(4, externalAmount);
            ps.setBigDecimal(5, amountDelta);
            ps.setInt(6, dateDeltaDays);
            return ps;
        });
    }
}
