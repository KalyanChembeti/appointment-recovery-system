package com.recoverysystem.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "recovery-system.worker.scheduling-enabled=true",
        "recovery-system.worker.recovery-poll-interval-ms=86400000",
        "recovery-system.worker.offer-expiry-poll-interval-ms=86400000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerSchedulerContextTest {

    private static final long TEST_POLL_INTERVAL_MS = 86_400_000L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private RecoveryWorkerScheduler recoveryWorkerScheduler;

    @Autowired
    private SlotOfferExpiryScheduler slotOfferExpiryScheduler;

    @Value("${recovery-system.worker.recovery-poll-interval-ms}")
    private long recoveryPollIntervalMs;

    @Value("${recovery-system.worker.offer-expiry-poll-interval-ms}")
    private long offerExpiryPollIntervalMs;

    @Test
    void enabledSchedulingCreatesBothSchedulerBeansWithResolvedPollIntervals() {
        assertNotNull(recoveryWorkerScheduler);
        assertNotNull(slotOfferExpiryScheduler);
        assertEquals(TEST_POLL_INTERVAL_MS, recoveryPollIntervalMs);
        assertEquals(TEST_POLL_INTERVAL_MS, offerExpiryPollIntervalMs);
    }
}
