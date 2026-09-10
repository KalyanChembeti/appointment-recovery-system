package com.recoverysystem.service;

import com.recoverysystem.repository.SlotOfferRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class SlotOfferExpiryWorkerService {

    private final SlotOfferRepository slotOfferRepository;
    private final SlotOfferExpiryOfferProcessor slotOfferExpiryOfferProcessor;

    public SlotOfferExpiryWorkerService(
            SlotOfferRepository slotOfferRepository,
            SlotOfferExpiryOfferProcessor slotOfferExpiryOfferProcessor) {
        this.slotOfferRepository = slotOfferRepository;
        this.slotOfferExpiryOfferProcessor = slotOfferExpiryOfferProcessor;
    }

    public int expireStaleOffers(int batchSize) {
        List<Long> slotOfferIds = slotOfferRepository.findStaleOfferedIds(
                Instant.now(), PageRequest.of(0, batchSize));

        int expiredCount = 0;
        for (Long slotOfferId : slotOfferIds) {
            if (slotOfferExpiryOfferProcessor.expireOfferIfEligible(slotOfferId)) {
                expiredCount++;
            }
        }
        return expiredCount;
    }
}
