# Testing Status

## Fresh verification result

On 2026-09-11, from the current working tree based on commit
`552505e86d0ee9ccc22d7c3017f2c6acbbd24ab0`, `mvn clean install` completed with:

```text
BUILD SUCCESS
Tests run: 258, Failures: 0, Errors: 0, Skipped: 0
Total time: 2:05 min
```

The verification environment used Maven 3.9.9 and Microsoft OpenJDK 21.0.12.1. All 25
test classes use Testcontainers with the `postgres:15` image. The fresh containers reported
PostgreSQL 15.19. No test uses H2 or another in-memory database.

`SchemaMigrationTest` deliberately does not start the Spring context; it runs Flyway
programmatically and uses JDBC metadata/queries. The other 24 classes use
`@SpringBootTest`, dynamic datasource properties, and PostgreSQL Testcontainers. Four
classes are marked `@Transactional` at class level and run each test in a rollback-bound
test transaction: `ReferenceEntityMappingTest`, `RecoveryJobEligibilityClassifierTest`,
`RecoveryCandidateSelectorTest`, and `RecoveryCandidateRevalidatorTest`.

Two tests start two workflow calls at the same time:
`DirectBookingServiceTest.concurrentOverlappingProviderBookingsProduceOneWinner` and
`RecoveryWorkerServiceTest.concurrentWorkersCreateOneOfferAndReturnHandledRaceOutcome`.
Each uses a fixed two-thread `ExecutorService`, a ready `CountDownLatch`, a start
`CountDownLatch`, and two real service transactions. Separate transaction calls made one
after another, including the W4 no-rollback and `REQUIRES_NEW` harnesses, are classified as
sequential and are not evidence that simultaneous interleavings are safe.

## Test-class inventory

| Test class | Methods | Production component | Main behavior proved | Execution classification |
|---|---:|---|---|---|
| `SchemaMigrationTest` | 15 | Flyway V1/V2 and PostgreSQL schema | tables/columns, extension, overlap constraints, uniqueness, statuses, policy seed, audit actor check | Sequential database integration; programmatic Flyway; Testcontainers PostgreSQL 15; no Spring context |
| `ReferenceEntityMappingTest` | 15 | All 12 entities/repositories | context validation, repository round trips, JSONB, appointment checks, unique email | Sequential Spring integration in rollback test transactions; Testcontainers PostgreSQL 15 |
| `DirectBookingServiceTest` | 12 | W1 `DirectBookingService` | success, eligibility, constraints, actor audit, one real race | 11 sequential Spring integration tests plus 1 true two-thread test; Testcontainers PostgreSQL 15 |
| `AppointmentCancellationServiceTest` | 11 | W2 cancellation and reconciliation | jobs, blocks, cascade, reasons, actors, rejections | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `AppointmentReschedulingServiceTest` | 16 | W3 reschedule and reconciliation | lock-dependent paths, destination validation, replacement/job state, rollback | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `OfferAcceptanceOrchestratorTest` | 16 | W4 orchestration/main transaction | success, cleanup, terminal commits, rollback cleanup, actors, rejections | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `OfferAcceptanceOnBehalfTest` | 2 | W13A calling convention through W4 orchestrator | correct patient appointment, receptionist audit actor, ownership rejection | Sequential committed Spring integration with audit-ID baselines; Testcontainers PostgreSQL 15 |
| `AcceptedOfferTerminalStateResolverTest` | 9 | W4 terminal resolver/expiry | six status/time paths and rollback configuration | Sequential Spring integration using separate transaction harness calls; Testcontainers PostgreSQL 15 |
| `SlotOfferCleanupDiscoveryTest` | 9 | W4 Category B/C/D discovery/cleanup | membership, overlap, dedupe/order, conditional cancellation | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `OfferAcceptancePatientConflictCleanupTest` | 8 | W4 `REQUIRES_NEW` cleanup | offer cancellation, job preservation, no-op cases, proxy | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `SlotOfferDeclineServiceTest` | 8 | W5 decline | success, every non-OFFERED state, untouched job/entry | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `SlotOfferExpiryOfferProcessorTest` | 8 | W6 per-offer expiry | stale transition/audit and terminal/future/missing skips | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `SlotOfferExpiryWorkerServiceTest` | 3 | W6 expiry batch coordinator | eligibility, count limit, oldest-first discovery | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `WaitlistEntryCreationServiceTest` | 11 | W7 creation | anchor/type/owner/provider/date validations and defaults | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `WaitlistEntryModificationServiceTest` | 11 | W8 modification | locks/rejections, preferences, JSON audit, actor modes | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `WaitlistEntryRemovalServiceTest` | 8 | W9 removal | entry/offer transitions, terminal skips, actors | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `AppointmentCompletionServiceTest` | 8 | W10 completion | time/status, reconciliation, audit, no job | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `AppointmentNoShowServiceTest` | 8 | W11 no-show | future success, reconciliation reason, USER actor, no job | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `RecoveryJobEligibilityClassifierTest` | 7 | W12 classifier | precedence, occupancy, block states, lead time | Sequential Spring integration in rollback test transactions; Testcontainers PostgreSQL 15 |
| `RecoveryCandidateSelectorTest` | 15 | W12 candidate query/ranking | every filter, historical offer scope, strict time rule, tie order | Sequential Spring integration in rollback test transactions; Testcontainers PostgreSQL 15 |
| `RecoveryCandidateRevalidatorTest` | 16 | W12 post-lock checks | every stale condition, prior-offer scope, no mutation | Sequential Spring integration in rollback test transactions; Testcontainers PostgreSQL 15 |
| `RecoveryWorkerServiceTest` | 12 | W12 worker transaction | all normal outcomes except stale integration, policy duration, ordering, stale offered-job routing, handled same-job race | 11 sequential committed Spring integration tests plus 1 true two-thread test; Testcontainers PostgreSQL 15 |
| `SchedulerReassignmentServiceTest` | 14 | W13B scheduler reassignment | type selection, eligibility, complete offer terminal matrix, job state, rollback, exact audits, untouched waitlist entry | Sequential committed Spring integration with delta-based audit assertions; Testcontainers PostgreSQL 15 |
| `ProviderBlockCreationServiceTest` | 9 | W14 request/creation | PENDING/ACTIVE choice and suppression cascade | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| `ProviderBlockActivationServiceTest` | 7 | W14 activation | conflict recheck, transition, suppression, actors, rejections | Sequential committed Spring integration; Testcontainers PostgreSQL 15 |
| **Total** | **258** |  | **0 failures, 0 errors, 0 skipped** |  |

## Full test method result list

Every method below passed in the fresh build. The list makes the evidence boundary explicit;
a method name documents the scenario that was executed, not a broader concurrency claim.

### `AcceptedOfferTerminalStateResolverTest` - 9 passed

- PASS `offeredAndUnexpiredReturnsNormallyWithoutChangesOrAudit`
- PASS `acceptedThrowsAlreadyAcceptedWithoutChangesOrAudit`
- PASS `declinedThrowsAlreadyResolvedAndReportsActualStatus`
- PASS `cancelledThrowsAlreadyResolvedAndReportsActualStatus`
- PASS `alreadyExpiredThrowsExpiredWithoutChangesOrAudit`
- PASS `staleOfferedAggressivelyExpiresAndAuditsBeforeThrowing`
- PASS `noRollbackHarnessCommitsAggressiveExpiryDespiteException`
- PASS `defaultRollbackHarnessRollsBackAggressiveExpiry`
- PASS `nonexistentOfferViaHarnessThrowsNotFound`

### `AppointmentCancellationServiceTest` - 11 passed

- PASS `simpleCancellationCreatesOpenRecoveryJobAndTwoAuditRows`
- PASS `twoActiveEntriesAndOffersAreReconciledAndFullyAudited`
- PASS `declinedOfferIsUntouchedWhileItsActiveEntryIsRemoved`
- PASS `entryAnchoredToAnotherAppointmentIsUntouchedAndUnaudited`
- PASS `activeOverlappingBlockSuppressesJobCreationButStillReconciles`
- PASS `pendingOverlappingBlockDoesNotSuppressRecoveryJobCreation`
- PASS `alreadyCancelledAppointmentIsRejectedWithoutChanges`
- PASS `rescheduledReasonIsRejectedWithoutChanges`
- PASS `nonexistentAppointmentIsRejected`
- PASS `actorAttributionIsConsistentAcrossEachCancellationCascade`
- PASS `patientAndStaffCancellationReasonsBothPersist`

### `AppointmentCompletionServiceTest` - 8 passed

- PASS `pastScheduledAppointmentCompletesWithExactlyOneAudit`
- PASS `completionReconcilesTwoEntriesAndOneOfferedSlotWithFourAudits`
- PASS `completionCancelsOnlyOfferedSlotAndLeavesDeclinedSlotUntouched`
- PASS `cancelledAppointmentIsRejectedAsNotScheduled`
- PASS `futureAppointmentIsRejectedAsNotYetStarted`
- PASS `nonexistentAppointmentIsRejected`
- PASS `actorAttributionIsConsistentAcrossTheFullCompletionCascade`
- PASS `successfulCompletionDoesNotCreateRecoveryJob`

### `AppointmentNoShowServiceTest` - 8 passed

- PASS `scheduledAppointmentIsMarkedNoShowWithExactlyOneAudit`
- PASS `noShowReconcilesTwoActiveEntriesAndOneOfferedSlotWithFourAudits`
- PASS `noShowCancelsOnlyOfferedSlotAndLeavesDeclinedSlotUntouched`
- PASS `nonScheduledAppointmentIsRejected`
- PASS `nonexistentAppointmentIsRejected`
- PASS `futureScheduledAppointmentCanStillBeMarkedNoShow`
- PASS `auditActorIsUserAcrossFullNoShowCascade`
- PASS `successfulNoShowDoesNotCreateRecoveryJob`

### `AppointmentReschedulingServiceTest` - 16 passed

- PASS `sameProviderRescheduleCreatesReplacementRecoveryJobAndThreeAudits`
- PASS `differentProviderRescheduleReconcilesTwoEntriesAndOfferedSlot`
- PASS `declinedSiblingOfferIsUntouchedAndUnaudited`
- PASS `activeBlockOnNewProviderRejectsAndRollsBack`
- PASS `pendingBlockOnNewProviderAlsoRejectsAndRollsBack`
- PASS `activeBlockOnOldIntervalAllowsRescheduleButSuppressesRecoveryJob`
- PASS `newAppointmentTypeSpecialtyMismatchIsRejected`
- PASS `derivedNewIntervalOutsideWorkingHoursIsRejected`
- PASS `oldAppointmentNotScheduledIsRejected`
- PASS `nonexistentOldAppointmentIsRejected`
- PASS `nonexistentNewProviderIsRejected`
- PASS `overlapWithDifferentAppointmentForSamePatientRollsBack`
- PASS `overlapWithOldAppointmentsOwnIntervalIsKnownCleanFailure`
- PASS `overlapWithDifferentPatientsAppointmentForNewProviderRollsBack`
- PASS `actorAttributionIsConsistentAcrossEachFullCascade`
- PASS `replacementEndUsesNewAppointmentTypesDuration`

### `DirectBookingServiceTest` - 12 passed

- PASS `validBookingCreatesScheduledAppointmentAndAuditLog`
- PASS `mismatchedAppointmentTypeAndProviderSpecialtiesAreRejected`
- PASS `nonexistentAppointmentTypeIsRejected`
- PASS `overlappingProviderBookingThrowsProviderDoubleBookedAndRollsBack`
- PASS `overlappingPatientBookingThrowsPatientDoubleBooked`
- PASS `adjacentProviderBookingsBothSucceed`
- PASS `activeProviderUnavailabilityRejectsBooking`
- PASS `pendingProviderUnavailabilityAlsoRejectsBooking`
- PASS `cancelledProviderUnavailabilityDoesNotRejectBooking`
- PASS `bookingOutsideProviderWorkingHoursIsRejected`
- PASS `nonexistentProviderIsRejected`
- PASS `concurrentOverlappingProviderBookingsProduceOneWinner` - true two-thread test using
  `ExecutorService` and `CountDownLatch`

### `OfferAcceptanceOrchestratorTest` - 16 passed

- PASS `acceptsOfferAndMovesPatientToNewAppointment`
- PASS `acceptsOfferAndCleansAllRelatedOffersAndSibling`
- PASS `activeDestinationBlockRejectsWithoutChanges`
- PASS `pendingDestinationBlockRejectsWithoutChanges`
- PASS `oldAppointmentMustStillBeScheduled`
- PASS `wrongPatientIsRejectedWithoutChanges`
- PASS `recoveryJobMustStillBeOpen`
- PASS `alreadyAcceptedOfferExceptionPropagates`
- PASS `expiredOfferCommitsExpiryBeforeException`
- PASS `occupiedProviderCommitsOfferAndJobTerminalStates`
- PASS `patientConflictRollsBackAcceptanceThenCancelsOfferSeparately`
- PASS `activeOldIntervalBlockSkipsRecoveryJob`
- PASS `pendingOldIntervalBlockStillCreatesRecoveryJob`
- PASS `fulfilledSiblingIsRejected`
- PASS `auditCascadeUsesSystemOrUserActor`
- PASS `missingSlotOfferIsRejected`

### `OfferAcceptanceOnBehalfTest` - 2 passed

- PASS `receptionistCanAcceptOfferForThePatientWhoOwnsIt`
- PASS `receptionistCannotAcceptOfferForPatientWhoDoesNotOwnIt`

### `OfferAcceptancePatientConflictCleanupTest` - 8 passed

- PASS `cancelsOfferedSlotAndKeepsOpenRecoveryJobUnchanged`
- PASS `cancelsOfferedSlotAndKeepsSuppressedRecoveryJobUnchanged`
- PASS `leavesDeclinedOfferUnchanged`
- PASS `leavesCancelledOfferUnchanged`
- PASS `leavesExpiredOfferUnchanged`
- PASS `missingRecoveryJobDoesNothing`
- PASS `missingSlotOfferDoesNothing`
- PASS `cleanupServiceIsSpringProxied`

### `ProviderBlockActivationServiceTest` - 7 passed

- PASS `resolvedConflictActivatesPendingBlockWithOneNewAudit`
- PASS `resolvedConflictActivationSuppressesJobAndCancelsOfferWithThreeNewAudits`
- PASS `unresolvedConflictRejectsActivationWithoutChangesOrNewAudits`
- PASS `nonexistentBlockIsRejected`
- PASS `alreadyActiveBlockIsRejectedWithoutChanges`
- PASS `cancelledBlockIsRejectedAsNotPendingWithoutChanges`
- PASS `actorAttributionAppliesToActivationAndCascadeAudits`

### `ProviderBlockCreationServiceTest` - 9 passed

- PASS `noConflictsAndNoRecoveryJobsCreatesActiveBlockWithOnlyCreateAudit`
- PASS `activeBlockSuppressesAffectedOpenRecoveryJobAndCancelsOfferedSlot`
- PASS `mixedSlotOfferStatusesCancelAndAuditOnlyOfferedSibling`
- PASS `nonOverlappingRecoveryJobRemainsOpenAndUnaudited`
- PASS `alreadySuppressedOverlappingRecoveryJobIsNotTouchedOrAuditedAgain`
- PASS `scheduledConflictCreatesPendingBlockAndLeavesRecoveryStateUntouched`
- PASS `nonexistentProviderIsRejected`
- PASS `nonIncreasingBlockIntervalIsRejected`
- PASS `auditActorUsesSystemForNullAndUserForNonNullAcrossEachCascade`

### `RecoveryCandidateRevalidatorTest` - 16 passed

- PASS `fullyEligibleCandidateReturnsTrue`
- PASS `inactiveCandidateReturnsFalse`
- PASS `mismatchedAppointmentTypeReturnsFalse`
- PASS `dateBeforeEarliestReturnsFalse`
- PASS `dateAfterLatestReturnsFalse`
- PASS `bothDateBoundariesAreInclusive`
- PASS `missingAnchorReturnsFalse`
- PASS `nonScheduledAnchorReturnsFalse`
- PASS `anchorPatientMismatchReturnsFalse`
- PASS `releasedIntervalAfterAnchorReturnsFalseWithoutPatientConflict`
- PASS `overlappingPatientAppointmentReturnsFalse`
- PASS `priorOfferForSameJobThroughAnotherPatientEntryReturnsFalse`
- PASS `priorOfferForDifferentJobDoesNotExcludeCandidate`
- PASS `offerForDifferentPatientOnSameJobDoesNotExcludeCandidate`
- PASS `revalidatorAndNewRepositoryQueriesDoNotDeclareTransactionsOrLocks`
- PASS `successfulCheckDoesNotChangeCandidateOrDatabase`

### `RecoveryCandidateSelectorTest` - 15 passed

- PASS `findsBasicEligibleCandidate`
- PASS `removedAndFulfilledEntriesAreExcluded`
- PASS `entryWithCancelledAnchorIsExcluded`
- PASS `mismatchedAppointmentTypeIsExcluded`
- PASS `dateWindowIsInclusiveAndRejectsDatesOutsideIt`
- PASS `patientScheduleConflictExcludesCandidate`
- PASS `priorOfferForSameJobExcludesPatientButDifferentJobDoesNot`
- PASS `overlappingAnchorIsExcludedByStrictEndBeforeStartRule`
- PASS `releasedIntervalAfterAnchorIsExcludedWithoutConflictCoFiring`
- PASS `olderCandidateWinsFifoRanking`
- PASS `matchingTimePreferenceWinsCreatedAtTie`
- PASS `anyPreferenceRanksWithExactMatchAndAheadOfMismatch`
- PASS `matchingAndNullProviderPreferencesRankAheadOfDifferentProvider`
- PASS `lowerIdWinsWhenAllRankingValuesTie`
- PASS `returnsEmptyWhenNoCandidateIsEligible`

### `RecoveryJobEligibilityClassifierTest` - 7 passed

- PASS `occupiedIntervalFillsJobAndWritesAudit`
- PASS `activeBlockSuppressesJob`
- PASS `pendingBlockLeavesJobCompletelyUnchanged`
- PASS `closedLeadTimeSuppressesJob`
- PASS `eligibleJobContinuesWithoutChangesOrAudit`
- PASS `cancelledBlockDoesNotStopEligibleJob`
- PASS `occupiedIntervalWinsWhenActiveBlockAlsoExists`

### `RecoveryWorkerServiceTest` - 12 passed

- PASS `noOpenJobsReturnsWithoutDatabaseChangesOrLockingRoutingRead`
- PASS `occupiedReleasedIntervalFillsJob`
- PASS `activeProviderBlockSuppressesJob`
- PASS `pendingProviderBlockLeavesJobUnchanged`
- PASS `closedLeadTimeSuppressesJob`
- PASS `noEligibleCandidateExhaustsJobAndWritesAudit`
- PASS `eligibleCandidateCreatesOfferUsingPolicyDurationAndLeavesJobOpen`
- PASS `multipleCandidatesCreatesOfferForTopRankedCandidate`
- PASS `oldestOpenJobIsProcessedFirst`
- PASS `jobWithOfferedOfferIsSkippedForNextEligibleJob`
- PASS `offeredSlotCreatedAfterRoutingReturnsOfferAlreadyExistsWithoutChanges`
- PASS `concurrentWorkersCreateOneOfferAndReturnHandledRaceOutcome` - true two-thread test;
  both Futures complete and the result multiset is exactly one `OFFER_CREATED` plus one
  `OFFER_ALREADY_EXISTS_FOR_JOB`; exactly one SlotOffer remains

### `ReferenceEntityMappingTest` - 15 passed

- PASS `contextLoadsWithValidatedEntityMappings`
- PASS `userRepositoryRoundTrip`
- PASS `specialtyRepositoryRoundTrip`
- PASS `appointmentTypeRepositoryRoundTrip`
- PASS `providerRepositoryRoundTrip`
- PASS `providerScheduleRepositoryRoundTrip`
- PASS `appointmentRepositoryRoundTrip`
- PASS `providerUnavailabilityRepositoryRoundTrip`
- PASS `recoveryJobRepositoryRoundTrip`
- PASS `waitlistEntryRepositoryRoundTrip`
- PASS `slotOfferRepositoryRoundTrip`
- PASS `schedulingPolicyRepositoryRoundTrip`
- PASS `auditLogRepositoryRoundTripPreservesJsonbText`
- PASS `appointmentCancellationReasonMismatchIsRejectedByDatabase`
- PASS `duplicateUserEmailIsRejected`

### `SchedulerReassignmentServiceTest` - 14 passed

- PASS `cancelledOfferIsRejectedAsAlreadyResolved`
- PASS `acceptedOfferIsRejectedWithoutChanges`
- PASS `nonexistentOfferIsRejected`
- PASS `successfulReassignmentDoesNotChangeOriginalWaitlistEntry`
- PASS `staleOfferedSlotIsExpiredAndCommittedBeforeException`
- PASS `appointmentTypeWithDifferentSpecialtyIsRejectedWithoutChanges`
- PASS `providerConflictRollsBackAndLeavesOfferOffered`
- PASS `recoveryJobThatIsNotOpenIsRejectedWithoutChanges`
- PASS `declinedOfferIsRejectedAsAlreadyResolved`
- PASS `nullAppointmentTypeDefaultsToSourceTypeAndCreatesThreeAudits`
- PASS `suppliedAppointmentTypeUsesItsOwnDurationForEndTime`
- PASS `activeProviderBlockIsRejectedWithoutChanges`
- PASS `patientConflictRollsBackAndLeavesOfferOffered`
- PASS `pendingProviderBlockIsAlsoRejectedWithoutChanges`

### `SchemaMigrationTest` - 15 passed

- PASS `migrationCreatesExpectedReferenceTablesAndColumns`
- PASS `specialtyNameMustBeUnique`
- PASS `usersRoleRejectsInvalidValue`
- PASS `providerUserIdMustReferenceExistingUser`
- PASS `migrationCreatesCoreWorkflowTablesWithPrimaryKeys`
- PASS `appointmentHasBothExclusionConstraints`
- PASS `btreeGistExtensionIsInstalled`
- PASS `patientOverlapExclusionIsEnforced`
- PASS `providerOverlapExclusionIsEnforced`
- PASS `adjacentScheduledAppointmentsDoNotOverlap`
- PASS `providerUnavailabilityAcceptsValidStatusesAndRejectsInvalidStatus`
- PASS `recoveryJobSourceAppointmentMustBeUnique`
- PASS `onlyOneOfferedSlotOfferIsAllowedPerRecoveryJob`
- PASS `schedulingPolicyHasExactlyOneDefaultRow`
- PASS `auditLogActorRulesAreEnforced`

### `SlotOfferCleanupDiscoveryTest` - 9 passed

- PASS `findsSecondOfferedOfferForAcceptedEntry`
- PASS `findsOfferedOfferForSiblingEntry`
- PASS `findsOverlappingOfferForOtherActiveEntryOfSamePatient`
- PASS `ignoresNonOverlappingOfferForOtherActiveEntry`
- PASS `ignoresOverlappingOfferForDifferentPatient`
- PASS `categoryDExcludesEntryAnchoredToOldAppointment`
- PASS `combinesCleanupCategoriesWithoutDuplicatesAndSortsIds`
- PASS `cleanupCancelsOfferedOfferAndWritesAudit`
- PASS `cleanupLeavesResolvedOffersUntouched`

### `SlotOfferDeclineServiceTest` - 8 passed

- PASS `offeredSlotIsDeclinedWithOnePatientAudit`
- PASS `acceptedSlotIsRejectedWithoutChanges`
- PASS `secondDeclineAttemptIsRejectedWithoutAdditionalChanges`
- PASS `expiredSlotIsRejectedWithoutChanges`
- PASS `cancelledSlotIsRejectedWithoutChanges`
- PASS `nonexistentSlotOfferIsRejected`
- PASS `successfulDeclineLeavesRecoveryJobOpenAndUntouched`
- PASS `successfulDeclineLeavesWaitlistEntryActiveAndUntouched`

### `SlotOfferExpiryOfferProcessorTest` - 8 passed

- PASS `staleOfferedSlotExpiresWithOneSystemAudit`
- PASS `futureOfferedSlotIsUntouchedWithoutAuditOrException`
- PASS `acceptedOfferIsSilentlySkippedUnlikeWorkflow5Rejection`
- PASS `declinedOfferIsSilentlySkippedWithoutAuditOrException`
- PASS `doubleExpiryIsAnIdempotentSkipWithoutDuplicateAudit`
- PASS `cancelledOfferIsSilentlySkippedWithoutAuditOrException`
- PASS `nonexistentSlotOfferReturnsFalseWithoutExceptionOrAudit`
- PASS `expiryComponentsHaveRequiredTransactionsAndNoRecoveryJobReferences`

### `SlotOfferExpiryWorkerServiceTest` - 3 passed

- PASS `batchExpiresOnlyThreeEligibleOffersAndSkipsFutureAndAcceptedOffers`
- PASS `batchSizeLimitsExpiryToExactlyTwoOfFiveEligibleOffers`
- PASS `batchSelectsOldestEligibleOffersByCreatedAt`

### `WaitlistEntryCreationServiceTest` - 11 passed

- PASS `validWaitlistEntryCreatesActiveEntryAndAuditLog`
- PASS `nonexistentCurrentAppointmentIsRejected`
- PASS `anchorAppointmentThatIsNotScheduledIsRejected`
- PASS `anchorAppointmentOwnedByAnotherPatientIsRejected`
- PASS `appointmentTypeDifferentFromAnchorTypeIsRejected`
- PASS `preferredProviderWithMismatchedSpecialtyIsRejected`
- PASS `preferredProviderWithMatchingSpecialtyIsAccepted`
- PASS `nullPreferredProviderSkipsSpecialtyCheckAndSucceeds`
- PASS `nonexistentPreferredProviderIsRejectedWithExistingException`
- PASS `earliestDateAfterLatestDateIsRejected`
- PASS `nullPreferredTimeOfDayDefaultsToAny`

### `WaitlistEntryModificationServiceTest` - 11 passed

- PASS `activeEntryUpdatesAllFieldsAndAuditsBeforeAndAfterValues`
- PASS `anchorAppointmentThatIsNotScheduledIsRejectedWithoutChanges`
- PASS `inactiveEntryIsRejectedWithoutChanges`
- PASS `nonexistentWaitlistEntryIsRejected`
- PASS `preferredProviderWithMismatchedSpecialtyIsRejectedWithoutChanges`
- PASS `preferredProviderWithMatchingSpecialtyIsAccepted`
- PASS `nullPreferredProviderClearsExistingPreference`
- PASS `nonexistentPreferredProviderIsRejectedWithoutChanges`
- PASS `earliestDateAfterLatestDateIsRejectedWithoutChanges`
- PASS `nullPreferredTimeOfDayPersistsAny`
- PASS `auditActorUsesSystemForNullAndUserForNonNullActor`

### `WaitlistEntryRemovalServiceTest` - 8 passed

- PASS `activeEntryWithTwoOfferedSlotsIsRemovedAndFullyAudited`
- PASS `activeEntryWithNoSlotOffersIsRemovedWithOnlyRemoveAudit`
- PASS `mixedSiblingStatusesCancelAndAuditOnlyOfferedSlot`
- PASS `alreadyRemovedEntryIsRejectedWithoutChangingEntryOfferOrAudit`
- PASS `fulfilledEntryIsRejectedAsNotActive`
- PASS `nonexistentWaitlistEntryIsRejected`
- PASS `nullActorProducesSystemAuditRows`
- PASS `nonNullActorProducesUserAuditRows`

## Transaction harnesses used by tests

`src/test/java/com/recoverysystem/support/NoRollbackExpiryHarness.java` opens a
`READ_COMMITTED` transaction with `noRollbackFor=OfferExpiredException`, locks a SlotOffer
through `SlotOfferRepository.findByIdForUpdate(...)`, and invokes
`AcceptedOfferTerminalStateResolver.resolve(...)`. Its test proves an aggressive EXPIRED
transition and `SlotOffer`/`EXPIRE`/null audit commit even though the exception escapes.

`src/test/java/com/recoverysystem/support/DefaultRollbackExpiryHarness.java` performs the
same lock and resolver call under default rollback rules. Its test proves the EXPIRED
transition and audit roll back. These harness calls occur in separate transactions one at a
time; no threads, executor, latch, or barrier make them concurrent.

## Already validated

The current PostgreSQL suite validates these concrete areas:

- Flyway applies V1 and V2 from an empty PostgreSQL 15 database; Hibernate validates all 12
  entity mappings without generating DDL.
- `no_patient_overlap`, `no_provider_overlap`, adjacent half-open intervals,
  `one_offered_per_recovery`, unique recovery source, enum-like checks, audit actor checks,
  and reference foreign keys/uniqueness work in PostgreSQL.
- W1-W13B and W14 request/activation sequential success, rejection, state, audit, and
  rollback branches listed by method above pass.
- W4's stale-offer and provider-occupied `noRollbackFor` branches commit the intended rows;
  its patient-overlap branch rolls the main transaction back and then commits only the
  separate conflict cleanup.
- W12 filtering, historical offer exclusion per patient/job,
  `releasedEndAt <= anchor.startAt`, clinic-local ranking, and every individual revalidation
  predicate pass sequential integration tests.
- Two true concurrent cases are validated: two overlapping direct bookings for one Provider
  produce one committed winner and one `ProviderDoubleBookedException`; two recovery workers
  targeting one OPEN job produce one `OFFER_CREATED`, one
  `OFFER_ALREADY_EXISTS_FOR_JOB`, and one committed SlotOffer.

W13B is validated sequentially for its complete status/validation/constraint branches and
transaction outcomes. The suite does not validate W14 `PENDING -> CANCELLED` because no
cancellation operation exists.

## Still requires true concurrent race validation

The following cases have no current test that runs both operations simultaneously:

1. accept one offer versus accept the same offer;
2. accept one offer versus decline the same offer;
3. accept one offer versus expire the same offer;
4. accept two offers associated with the same patient/old Appointment;
5. cancel versus reschedule the same Appointment;
6. two bookings for the same patient/interval with different Providers;
7. booking versus provider-block request for one Provider/interval;
8. block activation versus recovery generation;
9. block activation versus direct booking;
10. recovery offer generation versus removal of the selected WaitlistEntry;
11. offer acceptance versus removal of a sibling WaitlistEntry;
12. mutation of a selected W12 candidate between ranking and its lock;
13. worker selection in the presence of permanently ineligible candidates under concurrent
    writes;
14. W4 acceptance losing a patient-overlap race, followed by the separate conflict cleanup;
15. the existing two-booking/same-Provider race with complete final audit and row-count
    assertions if Step 7 requires more than its current one-winner assertion set.

Each future race test must use explicit thread/executor coordination and bounded waits,
assert the exact exception/outcome for both tasks, query committed final rows and audits,
and fail on deadlock or timeout. Calling two service methods sequentially in separate
transactions is not enough.

## Build warnings and expected logs

The fresh build had no errors. It produced:

- a Java compiler warning during main and test compilation that annotation processing may
  be disabled by default in a future `javac` release;
- Spring Security's generated development-password warning because no custom security
  configuration exists;
- Hibernate/PostgreSQL constraint-violation log entries from negative tests that
  intentionally insert overlapping appointments, duplicate values, invalid enum-like
  values, bad audit actors, or other invalid rows.

All of those negative tests passed by observing the expected rejection. No test was skipped.
