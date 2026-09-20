package com.recoverysystem.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.recoverysystem.domain.enums.RecoveryWorkerOutcome;
import com.recoverysystem.service.RecoveryWorkerService;
import com.recoverysystem.service.SlotOfferExpiryWorkerService;
import org.junit.jupiter.api.Test;

class WorkerSchedulerTest {

    @Test
    void recoveryPollCallsAttemptRecoveryExactlyOnce() {
        RecoveryWorkerService recoveryWorkerService = mock(RecoveryWorkerService.class);
        when(recoveryWorkerService.attemptRecovery())
                .thenReturn(RecoveryWorkerOutcome.NO_OPEN_JOBS);
        RecoveryWorkerScheduler scheduler =
                new RecoveryWorkerScheduler(recoveryWorkerService, 60_000);

        scheduler.pollForRecovery();

        verify(recoveryWorkerService, times(1)).attemptRecovery();
        verifyNoMoreInteractions(recoveryWorkerService);
    }

    @Test
    void expiryPollCallsBatchServiceExactlyOnce() {
        SlotOfferExpiryWorkerService expiryWorkerService =
                mock(SlotOfferExpiryWorkerService.class);
        when(expiryWorkerService.expireStaleOffers(SlotOfferExpiryScheduler.DEFAULT_BATCH_SIZE))
                .thenReturn(2);
        SlotOfferExpiryScheduler scheduler =
                new SlotOfferExpiryScheduler(expiryWorkerService, 30_000);

        scheduler.pollForExpiredOffers();

        verify(expiryWorkerService, times(1))
                .expireStaleOffers(SlotOfferExpiryScheduler.DEFAULT_BATCH_SIZE);
        verifyNoMoreInteractions(expiryWorkerService);
    }

    @Test
    void schedulerExceptionsDoNotPropagate() {
        RecoveryWorkerService recoveryWorkerService = mock(RecoveryWorkerService.class);
        when(recoveryWorkerService.attemptRecovery())
                .thenThrow(new IllegalStateException("recovery failure"));
        SlotOfferExpiryWorkerService expiryWorkerService =
                mock(SlotOfferExpiryWorkerService.class);
        when(expiryWorkerService.expireStaleOffers(SlotOfferExpiryScheduler.DEFAULT_BATCH_SIZE))
                .thenThrow(new IllegalStateException("expiry failure"));
        RecoveryWorkerScheduler recoveryScheduler =
                new RecoveryWorkerScheduler(recoveryWorkerService, 60_000);
        SlotOfferExpiryScheduler expiryScheduler =
                new SlotOfferExpiryScheduler(expiryWorkerService, 30_000);

        assertDoesNotThrow(recoveryScheduler::pollForRecovery);
        assertDoesNotThrow(expiryScheduler::pollForExpiredOffers);

        verify(recoveryWorkerService, times(1)).attemptRecovery();
        verify(expiryWorkerService, times(1))
                .expireStaleOffers(SlotOfferExpiryScheduler.DEFAULT_BATCH_SIZE);
        verifyNoMoreInteractions(recoveryWorkerService, expiryWorkerService);
    }
}
