package com.recoverysystem.worker;

import com.recoverysystem.domain.enums.RecoveryWorkerOutcome;
import com.recoverysystem.service.RecoveryWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecoveryWorkerScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecoveryWorkerScheduler.class);

    private final RecoveryWorkerService recoveryWorkerService;
    private final long pollIntervalMs;

    public RecoveryWorkerScheduler(
            RecoveryWorkerService recoveryWorkerService,
            @Value("${recovery-system.worker.recovery-poll-interval-ms}") long pollIntervalMs) {
        this.recoveryWorkerService = recoveryWorkerService;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Scheduled(fixedDelayString = "${recovery-system.worker.recovery-poll-interval-ms}")
    void pollForRecovery() {
        // fixedDelay starts after this invocation finishes, so this method cannot overlap itself.
        // Attempt exactly one job per poll: looping could repeatedly select the same oldest job
        // when it cannot progress and keep this scheduler invocation running indefinitely.
        try {
            RecoveryWorkerOutcome outcome = recoveryWorkerService.attemptRecovery();
            if (outcome == RecoveryWorkerOutcome.OFFER_CREATED) {
                LOGGER.info("Recovery worker created a slot offer");
            } else {
                LOGGER.debug("Recovery worker poll completed with outcome {}", outcome);
            }
        } catch (Exception exception) {
            LOGGER.error(
                    "Recovery worker poll failed (poll interval: {} ms)",
                    pollIntervalMs,
                    exception);
        }
    }
}
