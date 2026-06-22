package com.ledger.reconciliation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the reconciliation engine on a fixed-delay schedule.
 * Kept separate from ReconciliationEngine so the engine can be called
 * directly in integration tests without the scheduler also firing.
 *
 * Only active when reconciliation.scheduling.enabled=true (default).
 * Disabled in tests via application.yml → ConditionalOnProperty.
 */
@Component
@ConditionalOnProperty(name = "reconciliation.scheduling.enabled",
                       havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationEngine engine;

    public ReconciliationScheduler(ReconciliationEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${reconciliation.schedule.engine-ms:60000}",
               initialDelayString = "${reconciliation.schedule.engine-initial-ms:10000}")
    public void run() {
        log.info("Scheduled reconciliation run starting");
        try {
            engine.reconcileOnce();
        } catch (Exception e) {
            // Log and swallow so the scheduler keeps running on the next tick.
            // A single failed run should not kill the background job.
            log.error("Reconciliation run failed — will retry on next schedule", e);
        }
    }
}
