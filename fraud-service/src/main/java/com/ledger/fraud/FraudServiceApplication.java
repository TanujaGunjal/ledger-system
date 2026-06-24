package com.ledger.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the fraud-service microservice.
 *
 * This service is responsible for:
 *   1. Consuming TransactionPosted events from the {@code ledger.transactions} Kafka topic.
 *   2. Running a configurable rule-based scoring engine against each event.
 *   3. Persisting {@code FraudCase} records for MEDIUM and HIGH risk transactions.
 *   4. Exposing REST endpoints for real-time fraud scoring, case querying, and analyst review.
 *
 * The service is intentionally stateless beyond the database — all configuration thresholds
 * are externalised in {@code application.yml} so rules can be tuned without recompilation.
 *
 * Design principles carried over from ledger-api and reconciliation-worker:
 *   - Plain JDBC via JdbcTemplate, no JPA/ORM.
 *   - Java Records for all domain objects.
 *   - Degraded-mode operation: never block a transaction due to an internal failure.
 */
@SpringBootApplication
@EnableScheduling
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
