package com.ledger.api.exception;

public class UnbalancedPostingException extends RuntimeException {
    public UnbalancedPostingException(String currency, java.math.BigDecimal net) {
        super(String.format(
            "Posting entries do not net to zero for currency %s (net=%s). " +
            "Every debit must have an equal and opposite credit.",
            currency, net.toPlainString()
        ));
    }
}
