package com.ledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * This test boots the full Spring context, which means it needs Postgres,
 * Redis, and Kafka reachable -- i.e. `docker-compose up -d` must already be
 * running before you run `mvn test`. We'll swap this for Testcontainers in
 * a later phase so tests don't depend on anything being manually started.
 */
@SpringBootTest
class LedgerApiApplicationTests {

    @Test
    void contextLoads() {
    }
}
