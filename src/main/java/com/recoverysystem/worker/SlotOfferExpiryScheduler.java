package com.recoverysystem.worker;

import com.recoverysystem.service.SlotOfferExpiryWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SlotOfferExpiryScheduler {

    static final int DEFAULT_BATCH_SIZE = 50;

    private static final Logger LOGGER = LoggerFactory.getLogger(SlotOfferExpiryScheduler.class);

    private final SlotOfferExpiryWorkerService slotOfferExpiryWorkerService;
    private final long pollIntervalMs;

    public SlotOfferExpiryScheduler(
            SlotOfferExpiryWorkerService slotOfferExpiryWorkerService,
            @Value("${recovery-system.worker.offer-expiry-poll-interval-ms}")
                    long pollIntervalMs) {
        this.slotOfferExpiryWorkerService = slotOfferExpiryWorkerService;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Scheduled(fixedDelayString = "${recovery-system.worker.offer-expiry-poll-interval-ms}")
    void pollForExpiredOffers() {
        // fixedDelay starts after this invocation finishes, so this method cannot overlap itself.
        try {
            int expiredOfferCount =
                    slotOfferExpiryWorkerService.expireStaleOffers(DEFAULT_BATCH_SIZE);
            if (expiredOfferCount > 0) {
                LOGGER.info("Expired {} stale slot offers", expiredOfferCount);
            } else {
                LOGGER.debug("No stale slot offers found");
            }
        } catch (Exception exception) {
            LOGGER.error(
                    "Slot-offer expiry poll failed (poll interval: {} ms, batch size: {})",
                    pollIntervalMs,
                    DEFAULT_BATCH_SIZE,
                    exception);
        }
    }
}
