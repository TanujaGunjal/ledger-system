package com.ledger.api.exception;

/**
 * Thrown when a DEBIT entry would reduce an account balance below zero.
 * Maps to HTTP 409 Conflict — the request is structurally valid but
 * cannot be fulfilled given the current account state.
 */
public class InsufficientFundsException extends RuntimeException {

    private final Long accountId;

    public InsufficientFundsException(Long accountId) {
        super("Insufficient funds in account " + accountId);
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }
}
