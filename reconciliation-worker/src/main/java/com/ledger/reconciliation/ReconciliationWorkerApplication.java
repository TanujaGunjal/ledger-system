package com.ledger.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reconciliation Worker — a separately deployable Spring Boot service.
 *
 * Connects to the SAME Postgres database as ledger-api (same schema, same
 * connection details). ledger-api can continue posting transactions even when
 * this service is down or restarting — they share only the database, not a
 * process boundary.
 *
 * Runs on port 9091. ledger-api runs on 9090.
 */
@SpringBootApplication
@EnableScheduling
public class ReconciliationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationWorkerApplication.class, args);
    }
}
