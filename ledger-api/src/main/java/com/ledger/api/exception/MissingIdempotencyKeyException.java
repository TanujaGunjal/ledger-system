package com.ledger.api.exception;

public class MissingIdempotencyKeyException extends RuntimeException {
    public MissingIdempotencyKeyException() {
        super("Idempotency-Key header is required for POST /api/v1/transactions");
    }
}
