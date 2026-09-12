# Workflow Implementation Guide

This guide maps the numbered workflows to the current Java and PostgreSQL implementation.
Every state, exception, audit string, and lock sequence below comes from the current source.
W13A mechanics are implemented and tested through the reusable W4 entry point, but its
authentication/authorization boundary is absent. W13B and the W14 `PENDING -> CANCELLED`
transition are absent; those entries are labeled accordingly.

## Workflow index

| Workflow | Name | Service class and entry method | Test class |
|---|---|---|---|
| W1 | Direct booking | `DirectBookingService.bookAppointment(...)` | `DirectBookingServiceTest` |
| W2 | Normal appointment cancellation | `AppointmentCancellationService.cancelAppointment(...)` | `AppointmentCancellationServiceTest` |
| W3 | Direct reschedule | `AppointmentReschedulingService.rescheduleAppointment(...)` | `AppointmentReschedulingServiceTest` |
| W4 | Offer acceptance | `OfferAcceptanceOrchestrator.acceptOffer(...)` -> `OfferAcceptanceService.acceptOfferTransaction(...)` | `OfferAcceptanceOrchestratorTest`; helper tests listed in W4 |
| W5 | Offer decline | `SlotOfferDeclineService.declineOffer(...)` | `SlotOfferDeclineServiceTest` |
| W6 | Offer expiry | `SlotOfferExpiryWorkerService.expireStaleOffers(...)` -> `SlotOfferExpiryOfferProcessor.expireOfferIfEligible(...)` | `SlotOfferExpiryWorkerServiceTest`, `SlotOfferExpiryOfferProcessorTest` |
| W7 | Waitlist entry creation | `WaitlistEntryCreationService.createWaitlistEntry(...)` | `WaitlistEntryCreationServiceTest` |
| W8 | Waitlist entry modification | `WaitlistEntryModificationService.modifyWaitlistEntry(...)` | `WaitlistEntryModificationServiceTest` |
| W9 | Waitlist entry removal | `WaitlistEntryRemovalService.removeWaitlistEntry(...)` | `WaitlistEntryRemovalServiceTest` |
| W10 | Appointment completion | `AppointmentCompletionService.completeAppointment(...)` | `AppointmentCompletionServiceTest` |
| W11 | Appointment no-show | `AppointmentNoShowService.markNoShow(...)` | `AppointmentNoShowServiceTest` |
| W12 | Recovery worker offer generation | `RecoveryWorkerService.attemptRecovery()` | `RecoveryWorkerServiceTest`, `RecoveryJobEligibilityClassifierTest`, `RecoveryCandidateSelectorTest`, `RecoveryCandidateRevalidatorTest` |
| W13A | Scheduler accepts on behalf | Reuses `OfferAcceptanceOrchestrator.acceptOffer(...)`; no separate entry point | `OfferAcceptanceOnBehalfTest` |
| W13B | Scheduler reassigns to another patient | No implementation | No test |
| W14 | Provider-block request and activation | `ProviderBlockCreationService.createProviderBlock(...)`; `ProviderBlockActivationService.activateProviderBlock(...)` | `ProviderBlockCreationServiceTest`, `ProviderBlockActivationServiceTest` |

## Shared transaction mechanisms

### Booking eligibility

`AppointmentBookingEligibilityValidator.resolveAndValidate(Provider, Long, Instant)` in
`src/main/java/com/recoverysystem/service/AppointmentBookingEligibilityValidator.java` is
called with a Provider that its caller has already locked.

1. It loads the `AppointmentType` with the ordinary
   `AppointmentTypeRepository.findById(...)`; absence throws
   `AppointmentTypeNotFoundException`.
2. It derives `endAt` by adding `durationMinutes` to `startAt`.
3. It compares `AppointmentType.specialtyId` to the locked `Provider.specialtyId`; mismatch
   throws `AppointmentTypeSpecialtyMismatchException`.
4. `ProviderUnavailabilityRepository.existsOverlappingActiveOrPendingBlock(...)` rejects
   either `ACTIVE` or `PENDING` overlap with `ProviderUnavailableException`.
5. `ProviderScheduleRepository.findActiveByProviderIdAndDayOfWeek(...)` loads active rows
   for the clinic-local start day. One row must contain the entire interval. A cross-midnight
   clinic-local interval, an interval with `startAt >= endAt`, or no containing schedule
   throws `ProviderUnavailableException`.
6. It returns `ResolvedBooking(appointmentTypeId, startAt, endAt)`.

W1 and W3 use this helper. W4 does not: it takes provider, interval, and appointment type
from the released source appointment and the accepted waitlist entry, then performs its own
block and occupancy checks.

### Appointment insert constraint translation

`BookingConstraintViolationTranslator.translate(DataIntegrityViolationException)` in
`src/main/java/com/recoverysystem/service/BookingConstraintViolationTranslator.java`
walks exception causes and messages:

- constraint `no_provider_overlap` becomes `ProviderDoubleBookedException`;
- constraint `no_patient_overlap` becomes `PatientDoubleBookedException`;
- an unrecognized `DataIntegrityViolationException` is returned unchanged.

W1, W3, and W4 call `AppointmentRepository.flush()` immediately after saving a new
appointment so these constraint failures happen inside the service's guarded block.

### Anchored waitlist reconciliation

`WaitlistReconciliationCascade.reconcileAnchoredWaitlistEntries(...)` in
`src/main/java/com/recoverysystem/service/WaitlistReconciliationCascade.java` is called only
after the caller has locked the anchor Appointment.

1. `WaitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(appointmentId,
   ACTIVE)` locks matching ACTIVE entries in ascending ID order.
2. If entries exist,
   `SlotOfferRepository.findByWaitlistEntryIdsAndStatusForUpdate(entryIds, OFFERED)` locks
   their OFFERED offers in ascending ID order.
3. Every selected entry changes `ACTIVE -> REMOVED` and gets an audit with
   `entityType='WaitlistEntry'`, `action='REMOVE'`, and `reason=null`.
4. Every selected offer changes `OFFERED -> CANCELLED` and gets an audit with
   `entityType='SlotOffer'`, `action='CANCEL'`, and the exact reason passed by the caller.
5. Entries not ACTIVE and offers not OFFERED are not selected, changed, or audited.

W2 passes `APPOINTMENT_CANCELLED`, W3 passes `APPOINTMENT_RESCHEDULED`, W10 passes
`APPOINTMENT_COMPLETED`, and W11 passes `APPOINTMENT_NOSHOW`.

### Actor attribution

W1-W4, W8-W10, W12, and W14 use `ActorType.SYSTEM` with `actorUserId=null` when their
contract permits a system actor; otherwise they use `ActorType.USER` with the supplied ID.
W5 and W7 always use the patient ID with `ActorType.USER`. W11 always uses
`ActorType.USER` with its required receptionist actor ID. W6 always uses
`ActorType.SYSTEM` with `actorUserId=null`. The database constraint
`audit_log_actor_valid` enforces the matching nullability.

## W1 - Direct booking

**Implementation.**
`src/main/java/com/recoverysystem/service/DirectBookingService.java` exposes
`bookAppointment(Long patientId, Long providerId, Long appointmentTypeId, Instant startAt,
Long actorUserId)` under `READ_COMMITTED`.

**Lock order and queries.** The only explicit lock is Provider through
`ProviderRepository.findByIdForUpdate(providerId)`. The method uses
`PESSIMISTIC_WRITE`; absence throws `ProviderNotFoundException`. AppointmentType,
ProviderUnavailability, and ProviderSchedule reads in the eligibility helper are unlocked.
The new Appointment is inserted and protected by database exclusion constraints.

**Validation and rejection outcomes.** After the Provider lock, the shared booking helper
can throw `AppointmentTypeNotFoundException`,
`AppointmentTypeSpecialtyMismatchException`, or `ProviderUnavailableException`. The
appointment insert can throw `ProviderDoubleBookedException` for
`no_provider_overlap`, `PatientDoubleBookedException` for `no_patient_overlap`, or the
original `DataIntegrityViolationException` for another integrity failure. Any rejection
rolls back the appointment and audit.

**Success state and audit.** The service inserts a `SCHEDULED` Appointment for the supplied
patient/provider and the helper-derived interval. It writes one audit:
`entityType='Appointment'`, `entityId=<new appointment id>`, `action='CREATE'`,
`reason=null`; the actor is SYSTEM/null when `actorUserId` is null and USER/the supplied ID
otherwise. W1 never creates a RecoveryJob.

**Tests.** `src/test/java/com/recoverysystem/DirectBookingServiceTest.java` has 12 tests for
success/audit, specialty mismatch, missing appointment type, provider and patient overlap,
adjacent intervals, ACTIVE/PENDING/CANCELLED blocks, working hours, missing provider, and a
true simultaneous same-provider booking race. The race uses two threads, an executor, and
latches and proves one success and one `ProviderDoubleBookedException`.

## W2 - Normal appointment cancellation

**Implementation.**
`src/main/java/com/recoverysystem/service/AppointmentCancellationService.java` exposes
`cancelAppointment(Long appointmentId, CancellationReason cancellationReason, Long
actorUserId)` under `READ_COMMITTED`.

**Lock order and queries.** An unlocked `AppointmentRepository.findById(appointmentId)` is
a routing read for `providerId`; absence throws `AppointmentNotFoundException`. The service
then locks:

1. Provider with `ProviderRepository.findByIdForUpdate(providerId)`; absence throws
   `ProviderNotFoundException`.
2. Appointment with `AppointmentRepository.findByIdForUpdate(appointmentId)`; disappearance
   throws `AppointmentNotFoundException`.
3. ACTIVE anchored entries with
   `WaitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(...)`, ascending ID.
4. Those entries' OFFERED offers with
   `SlotOfferRepository.findByWaitlistEntryIdsAndStatusForUpdate(...)`, ascending ID.

**Validation and rejection outcomes.** A locked Appointment whose status is not
`SCHEDULED` causes `AppointmentNotScheduledException`. A requested reason of
`CancellationReason.RESCHEDULED` causes `InvalidCancellationReasonException`; that reason
is reserved for replacement workflows. The intended normal reasons are
`PATIENT_CANCELLED` and `STAFF_CANCELLED`. There is no explicit null-reason validation; a
null reason reaches the database cancellation-state check and fails the transaction.

**Success state and audit.** Reconciliation first changes anchored ACTIVE entries to
REMOVED and OFFERED offers to CANCELLED. Their audit values are
`WaitlistEntry`/`REMOVE`/null and
`SlotOffer`/`CANCEL`/`APPOINTMENT_CANCELLED`. The Appointment then changes
`SCHEDULED -> CANCELLED`, keeps the supplied normal cancellation reason, and gets
`Appointment`/`CANCEL`/null.

`ProviderUnavailabilityRepository.existsOverlappingActiveBlock(...)` decides recovery:

- no overlapping ACTIVE block: insert an `OPEN` RecoveryJob for the cancelled Appointment
  and audit `RecoveryJob`/`CREATE`/null;
- overlapping ACTIVE block: do not create a RecoveryJob;
- overlapping PENDING block: the query does not treat it as ACTIVE, so create the OPEN job.

All cascade audits use the same SYSTEM/null or USER/supplied-ID actor selection.

**Tests.** `AppointmentCancellationServiceTest` has 11 tests for the simple job path, two
entries/offers, untouched DECLINED and unrelated rows, ACTIVE and PENDING block behavior,
already-cancelled rejection, forbidden RESCHEDULED reason, missing appointment, actor
consistency, and both permitted cancellation reasons.

## W3 - Direct reschedule

**Implementation.**
`src/main/java/com/recoverysystem/service/AppointmentReschedulingService.java` exposes
`rescheduleAppointment(Long oldAppointmentId, Long newProviderId, Long
newAppointmentTypeId, Instant newStartAt, Long actorUserId)` under `READ_COMMITTED`.

**Routing and complete lock order.**

1. `AppointmentRepository.findById(oldAppointmentId)` reads the old Appointment without a
   lock to obtain `oldProviderId` and the patient. Absence throws
   `AppointmentNotFoundException`.
2. Old and new provider IDs are deduplicated and sorted. One call to
   `ProviderRepository.findAllByIdInForUpdate(providerIds)` locks existing Providers in
   ascending ID order. If the new provider is absent, the service throws
   `ProviderNotFoundException(newProviderId)`. The code does not separately reject an absent
   old Provider from the returned map; the Appointment foreign key normally prevents that
   state.
3. The locked new Provider is passed to `AppointmentBookingEligibilityValidator`. Its
   AppointmentType, block, and schedule reads are unlocked but occur while the Provider
   synchronization row remains locked.
4. `AppointmentRepository.findByIdForUpdate(oldAppointmentId)` locks the old Appointment;
   absence throws `AppointmentNotFoundException`.
5. Reconciliation locks anchored ACTIVE WaitlistEntries in ascending ID order with
   `findByCurrentAppointmentIdAndStatusForUpdate(...)`, then locks their OFFERED SlotOffers
   in ascending ID order with `findByWaitlistEntryIdsAndStatusForUpdate(...)`.

The new Appointment and optional RecoveryJob are inserts and have no pre-existing rows to
lock.

**Validation and rejection outcomes.** The new destination validation can throw
`AppointmentTypeNotFoundException`, `AppointmentTypeSpecialtyMismatchException`, or
`ProviderUnavailableException`; both ACTIVE and PENDING destination blocks reject. The
locked old Appointment must still be `SCHEDULED` or the service throws
`AppointmentNotScheduledException`. The new insert translates `no_patient_overlap` to
`PatientDoubleBookedException` and `no_provider_overlap` to
`ProviderDoubleBookedException`; an unknown integrity violation remains
`DataIntegrityViolationException`. A known special case is a replacement interval that
overlaps the still-SCHEDULED old Appointment: the database rejects the new insert before
the old row is changed, producing the clean patient-overlap failure and rolling back all
prior reconciliation work.

**Success transition order and audit.**

1. Reconcile the old Appointment's ACTIVE entries and OFFERED offers. Audits are
   `WaitlistEntry`/`REMOVE`/null and
   `SlotOffer`/`CANCEL`/`APPOINTMENT_RESCHEDULED`.
2. Insert the replacement as `SCHEDULED` for the patient from the routing old Appointment,
   the requested provider, the requested type, and the helper-derived end time. Audit
   `Appointment`/`CREATE`/null for the new ID.
3. Change the old Appointment to `CANCELLED`, set
   `cancellationReason=RESCHEDULED`, and set `replacedByAppointmentId` to the new ID. Audit
   `Appointment`/`CANCEL`/null for the old ID.
4. Query the old interval with
   `ProviderUnavailabilityRepository.existsOverlappingActiveBlock(...)`. Without ACTIVE
   overlap, insert an OPEN RecoveryJob and audit `RecoveryJob`/`CREATE`/null. With ACTIVE
   overlap, create no job. PENDING overlap still permits job creation.

Every audit uses SYSTEM/null when `actorUserId` is null and USER/the supplied ID otherwise.
All mutations and audits roll back together for ordinary runtime failures.

**Tests.** `AppointmentReschedulingServiceTest` has 16 tests. It covers same/different
provider moves; replacement-job creation; full anchored-entry cleanup; untouched DECLINED
offers; ACTIVE and PENDING destination rejection; ACTIVE old-interval job suppression;
specialty mismatch; working hours; old status and missing old/new rows; patient and provider
overlap rollback; the old-interval self-overlap case; actor consistency; and deriving the
replacement end from the new AppointmentType duration.

## W4 - Offer acceptance

**Implementation and transaction boundary.** The public entry point is
`OfferAcceptanceOrchestrator.acceptOffer(Long slotOfferId, Long patientId, Long
actorUserId)` in `src/main/java/com/recoverysystem/service/OfferAcceptanceOrchestrator.java`.
It performs an unlocked offer routing read, captures `recoveryJobId`, and delegates to the
proxied `OfferAcceptanceService.acceptOfferTransaction(...)`. The inner method is
`READ_COMMITTED` with `noRollbackFor` for exactly `OfferExpiredException` and
`ProviderIntervalOccupiedException`. The orchestrator catches only
`PatientDoubleBookedException` so it can run post-rollback cleanup.

**Routing reads and complete lock order.** Before locks, the inner service reads the offer,
its RecoveryJob, its WaitlistEntry, the entry's current/old Appointment, and the job's
source/offered Appointment to discover all parent IDs and the offered interval. The
corresponding absence failures are `SlotOfferNotFoundException`,
`RecoveryJobNotFoundException`, `WaitlistEntryNotFoundException`, and
`AppointmentNotFoundException` for either Appointment.

It then acquires locks in this order:

1. Old and offered Providers: IDs are deduplicated and sorted, then
   `ProviderRepository.findAllByIdInForUpdate(ids)` locks them in ascending ID order. The
   offered Provider must be present or `ProviderNotFoundException` is thrown.
2. Old Appointment: `AppointmentRepository.findByIdForUpdate(oldAppointmentId)` locks the
   row; absence throws `AppointmentNotFoundException`.
3. RecoveryJob: `RecoveryJobRepository.findByIdForUpdate(recoveryJobId)` locks the row;
   absence throws `RecoveryJobNotFoundException`.
4. ACTIVE WaitlistEntries anchored to the old Appointment:
   `WaitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(oldAppointmentId,
   ACTIVE)` locks them in ascending ID order.
5. Accepted and cleanup SlotOffers: Category IDs are deduplicated and sorted, and
   `SlotOfferRepository.findAllByIdInForUpdate(allOfferIds)` locks them in ascending ID
   order. If the accepted ID is absent from the locked result,
   `SlotOfferNotFoundException` is thrown.

The provider-block, fulfilled-sibling, cleanup-discovery, and provider-occupancy queries are
ordinary reads executed while their parent locks are held.

**Validation before offer terminal-state resolution.**

1. `ProviderUnavailabilityRepository.existsOverlappingActiveOrPendingBlock(...)` rejects an
   ACTIVE or PENDING destination block with `ProviderUnavailableException`.
2. The locked old Appointment must be `SCHEDULED`; otherwise
   `AppointmentNotScheduledException` is thrown.
3. The routed WaitlistEntry patient must equal `patientId`; otherwise
   `OfferAcceptanceOwnershipException` is thrown. The check is repeated against the locked
   accepted entry.
4. The locked RecoveryJob must be `OPEN`; otherwise `RecoveryJobNotOpenException` reports
   its actual status.
5. The accepted entry must be present in the locked ACTIVE set; otherwise
   `WaitlistEntryNotActiveException` is thrown with the status seen by the routing read.
6. `WaitlistEntryRepository.findFulfilledByCurrentAppointmentId(...)` must return no row.
   If it returns one or more fulfilled sibling IDs, sorted IDs are included in
   `SiblingAlreadyFulfilledException`.

**Exact six-way accepted-offer state handling.** After all offer rows are locked,
`AcceptedOfferTerminalStateResolver.resolve(acceptedOffer)` produces one of these six
outcomes:

1. `status=ACCEPTED`: throw `OfferAlreadyAcceptedException`; do not change or audit the
   accepted offer.
2. `status=DECLINED`: throw `OfferAlreadyResolvedException` containing `DECLINED`; do not
   change or audit the accepted offer.
3. `status=CANCELLED`: throw `OfferAlreadyResolvedException` containing `CANCELLED`; do not
   change or audit the accepted offer.
4. `status=EXPIRED`: throw `OfferExpiredException`; do not add another expiry audit.
5. `status=OFFERED` and `expiresAt <= Instant.now()`: change it to `EXPIRED`, write exactly
   one audit with `entityType='SlotOffer'`, `action='EXPIRE'`, `reason=null`,
   `actorType=SYSTEM`, `actorUserId=null`, then throw `OfferExpiredException`. Because the
   transaction excludes that exception from rollback, the expiry and audit commit.
6. `status=OFFERED` and `expiresAt > Instant.now()`: return normally and continue
   acceptance without changing the offer yet.

`AcceptedOfferTerminalStateResolver` and `SlotOfferAggressiveExpiryTransition` do not open a
transaction or acquire a lock; their caller must supply the locked offer and the correct
transaction policy.

**Category A/B/C/D cleanup.** `SlotOfferCleanupDiscovery.discoverCleanupOfferIds(...)`
defines the cleanup set:

- Category A is the accepted offer itself. The service always adds its ID so it is locked
  and terminal-state-resolved, but it is excluded from superseded cleanup.
- Category B is every other currently OFFERED offer for the accepted WaitlistEntry.
- Category C is every currently OFFERED offer for a sibling ACTIVE WaitlistEntry anchored
  to the same old Appointment.
- Category D is every currently OFFERED offer for another ACTIVE WaitlistEntry belonging to
  the same patient, where that entry's `currentAppointmentId` differs from the old
  Appointment and the offer's RecoveryJob source Appointment overlaps the destination
  interval using `source.startAt < destinationEnd && source.endAt > destinationStart`.

Categories B and C are found by
`SlotOfferRepository.findOfferedIdsByWaitlistEntryIdsExcludingOffer(...)`. Category D is
found by
`SlotOfferRepository.findOfferedIdsForOtherActivePatientEntriesOverlapping(...)`. All IDs
are deduplicated, sorted, and locked with `findAllByIdInForUpdate(...)`. After the accepted
offer passes terminal resolution, every other locked offer is rechecked. A row still
`OFFERED` changes to `CANCELLED` and gets
`entityType='SlotOffer'`, `action='CANCEL'`,
`reason='SUPERSEDED_BY_ACCEPTANCE'`; a row that became ACCEPTED, DECLINED, EXPIRED, or
CANCELLED is unchanged and unaudited.

**Provider-occupied terminal path.** After cleanup locks and rechecks, the service calls
`AppointmentRepository.findScheduledOverlappingIds(offeredProviderId, offeredStartAt,
offeredEndAt)`. If it returns any Appointment ID:

1. accepted offer `OFFERED -> CANCELLED`; audit
   `SlotOffer`/`CANCEL`/`PROVIDER_INTERVAL_OCCUPIED` using the request actor;
2. RecoveryJob `OPEN -> FILLED`; set microsecond-truncated `filledAt`; audit
   `RecoveryJob`/`FILLED`/null using the request actor;
3. throw `ProviderIntervalOccupiedException`.

The no-rollback rule commits those terminal states and any prior Category B/C/D cleanup.
No Appointment or WaitlistEntry is changed on this branch because occupancy is checked
before those mutations.

**Successful acceptance state and audit sequence.** When the provider interval is free:

1. Insert a new `SCHEDULED` Appointment for `patientId`, the offered source Appointment's
   provider and exact interval, and the accepted WaitlistEntry's AppointmentType. Audit the
   new ID as `Appointment`/`CREATE`/null.
2. Change the old Appointment `SCHEDULED -> CANCELLED`, set
   `cancellationReason=RESCHEDULED`, set `replacedByAppointmentId=<new id>`, and audit
   `Appointment`/`CANCEL`/null.
3. Change the accepted WaitlistEntry `ACTIVE -> FULFILLED` and audit
   `WaitlistEntry`/`FULFILL`/null.
4. Change every sibling entry in the locked ACTIVE set `ACTIVE -> REMOVED` and audit each
   as `WaitlistEntry`/`REMOVE`/null.
5. Change the accepted offer `OFFERED -> ACCEPTED`, set microsecond-truncated `acceptedAt`,
   and audit `SlotOffer`/`ACCEPT`/null.
6. Change the accepted RecoveryJob `OPEN -> FILLED`, set `filledAt` to the same instant as
   `acceptedAt`, and audit `RecoveryJob`/`FILLED`/null.
7. If the old interval has no overlapping ACTIVE ProviderUnavailability, insert an OPEN
   RecoveryJob for the old Appointment and audit `RecoveryJob`/`CREATE`/null. ACTIVE overlap
   suppresses creation; PENDING overlap permits creation.

The request actor is SYSTEM/null when `actorUserId` is null and USER/the supplied ID
otherwise. Category cleanup and all success audits use that same actor.

**Appointment-insert failure paths.** The new insert uses
`BookingConstraintViolationTranslator`:

- `no_patient_overlap` produces `PatientDoubleBookedException`. The entire main acceptance
  transaction rolls back, including Category cleanup and every state/audit mutation. Once
  rollback has completed, `OfferAcceptanceOrchestrator` calls
  `OfferAcceptancePatientConflictCleanup.cleanupAfterPatientConflict(...)` in a separate
  `READ_COMMITTED`, `REQUIRES_NEW` transaction. That method locks RecoveryJob with
  `RecoveryJobRepository.findByIdForUpdate(...)`, then SlotOffer with
  `SlotOfferRepository.findByIdForUpdate(...)`. It deliberately leaves the RecoveryJob
  unchanged. If both rows exist and the offer is still OFFERED, it changes it to CANCELLED
  and writes `SlotOffer`/`CANCEL`/`PATIENT_SCHEDULE_CONFLICT` with the request actor. A
  missing job, missing offer, or non-OFFERED offer makes cleanup a no-op. The orchestrator
  then rethrows `PatientDoubleBookedException`.
- `no_provider_overlap` produces `ProviderDoubleBookedException` and the main transaction
  rolls back; the orchestrator does not run patient-conflict cleanup.
- another integrity violation remains `DataIntegrityViolationException` and rolls back.

**Tests.** `OfferAcceptanceOrchestratorTest` has 16 tests for success; Category cleanup;
ACTIVE/PENDING blocks; old status; ownership; job status; accepted and expired terminal
states; provider occupancy commit; patient-overlap rollback plus separate cleanup; ACTIVE
and PENDING old-interval recovery behavior; fulfilled sibling rejection; SYSTEM/USER audit
attribution; and missing offer. `AcceptedOfferTerminalStateResolverTest` has 9 tests for all
terminal states, stale/unexpired behavior, no-rollback versus default rollback, and missing
offer through harnesses. `SlotOfferCleanupDiscoveryTest` has 9 tests for Categories B/C/D,
deduplication/order, cleanup, and resolved-row no-op behavior.
`OfferAcceptancePatientConflictCleanupTest` has 8 tests for OPEN/SUPPRESSED job preservation,
non-OFFERED no-ops, missing rows, and Spring proxying. These are sequential transaction
tests; they do not execute two acceptance/decline/expiry requests simultaneously.

## W5 - Offer decline

**Implementation.** `SlotOfferDeclineService.declineOffer(Long slotOfferId, Long patientId)`
runs under `READ_COMMITTED`.

**Lock and validation.** `SlotOfferRepository.findByIdForUpdate(slotOfferId)` locks the one
offer with `PESSIMISTIC_WRITE`; absence throws `SlotOfferNotFoundException`. Any actual
status other than OFFERED, including ACCEPTED, DECLINED, EXPIRED, or CANCELLED, throws one
generic `SlotOfferNotOfferedException` containing the actual status. The service does not
check whether the offer's WaitlistEntry belongs to `patientId`.

**State and audit.** Success changes `OFFERED -> DECLINED` and writes exactly one audit:
`entityType='SlotOffer'`, `action='DECLINE'`, `reason=null`, `actorType=USER`,
`actorUserId=patientId`. RecoveryJob and WaitlistEntry are untouched. A second decline is a
rejection, not an idempotent success.

**Tests.** `SlotOfferDeclineServiceTest` has 8 tests covering success/audit, each of the four
non-OFFERED states, missing ID, unchanged OPEN RecoveryJob, and unchanged ACTIVE
WaitlistEntry.

## W6 - Offer expiry

**Implementation.** `SlotOfferExpiryWorkerService.expireStaleOffers(int batchSize)` is an
untransactional coordinator. It asks
`SlotOfferRepository.findStaleOfferedIds(Instant.now(), PageRequest.of(0, batchSize))` for
OFFERED rows with `expiresAt < now`, ordered by `createdAt`. It calls
`SlotOfferExpiryOfferProcessor.expireOfferIfEligible(id)` once per ID. The processor opens a
separate `READ_COMMITTED` transaction for each offer.

**Lock and exact outcomes.** The processor locks one offer with
`SlotOfferRepository.findByIdForUpdate(id)`:

1. missing row: return `false`, no audit;
2. status ACCEPTED, DECLINED, EXPIRED, or CANCELLED after the lock: return `false`, no
   change or audit;
3. status OFFERED but `expiresAt > Instant.now()`: return `false`, no change or audit;
4. status OFFERED and `expiresAt <= Instant.now()`: change to EXPIRED, write
   `SlotOffer`/`EXPIRE`/null with SYSTEM/null, and return `true`.

The outer method counts only `true` results. RecoveryJob and WaitlistEntry are never
changed. Unlike W5, a repeated or terminal-state expiry is an idempotent skip and throws no
domain exception. Discovery uses `<` while the locked transition uses `<=`; a row exactly
at the comparison instant may be found on a later poll.

**Tests.** `SlotOfferExpiryOfferProcessorTest` has 8 tests for stale success, future and all
terminal skips, repeated expiry, missing row, audit, transaction annotations, and absence
of RecoveryJob references. `SlotOfferExpiryWorkerServiceTest` has 3 tests for mixed-row
batch behavior, batch size, and oldest-created ordering. No `@Scheduled` adapter currently
invokes the worker.

## W7 - Waitlist entry creation

**Implementation.** `WaitlistEntryCreationService.createWaitlistEntry(Long patientId, Long
currentAppointmentId, Long appointmentTypeId, LocalDate earliestDate, LocalDate latestDate,
Long preferredProviderId, TimeOfDayPreference preferredTimeOfDay)` runs under
`READ_COMMITTED`.

**Lock and validation order.** It locks the anchor with
`AppointmentRepository.findByIdForUpdate(currentAppointmentId)`; absence throws
`AppointmentNotFoundException`. The anchor must be SCHEDULED or
`WaitlistAnchorNotScheduledException` is thrown. Its patient must equal `patientId` or
`WaitlistAnchorOwnershipException` is thrown. Its AppointmentType must equal
`appointmentTypeId` or `WaitlistAppointmentTypeMismatchException` is thrown.

When `preferredProviderId` is non-null, ordinary repository reads require that Provider
(`ProviderNotFoundException`) and AppointmentType (`AppointmentTypeNotFoundException`) to
exist and share `specialtyId`; mismatch throws
`PreferredProviderSpecialtyMismatchException`. `earliestDate > latestDate` throws
`InvalidWaitlistDateRangeException`. A null time preference is stored as ANY.

**State and audit.** It inserts an ACTIVE WaitlistEntry with the supplied date/provider
preferences and writes `WaitlistEntry`/`CREATE`/null with `actorType=USER` and
`actorUserId=patientId`. It does not create or change any offer or RecoveryJob.

**Tests.** `WaitlistEntryCreationServiceTest` has 11 tests for success; missing, inactive,
wrong-owner, and wrong-type anchors; preferred-provider missing/matching/mismatch/null;
invalid dates; and null-to-ANY behavior.

## W8 - Waitlist entry modification

**Implementation.** `WaitlistEntryModificationService.modifyWaitlistEntry(Long
waitlistEntryId, LocalDate newEarliestDate, LocalDate newLatestDate, Long
newPreferredProviderId, TimeOfDayPreference newPreferredTimeOfDay, Long actorUserId)` runs
under `READ_COMMITTED`.

**Routing, locks, and validation.** An unlocked
`WaitlistEntryRepository.findById(waitlistEntryId)` supplies the routed anchor ID; absence
throws `WaitlistEntryNotFoundException`. Then:

1. `AppointmentRepository.findByIdForUpdate(routedAppointmentId)` locks the anchor;
   absence throws `AppointmentNotFoundException`, and non-SCHEDULED status throws
   `WaitlistAnchorNotScheduledException`.
2. `WaitlistEntryRepository.findByIdForUpdate(waitlistEntryId)` locks the entry; absence
   throws `WaitlistEntryNotFoundException`, non-ACTIVE status throws
   `WaitlistEntryNotActiveException`, and a current anchor ID different from the routing ID
   throws `WaitlistEntryAnchorMismatchException`.

For a non-null preferred Provider, ordinary reads can throw `ProviderNotFoundException` or
`AppointmentTypeNotFoundException`; specialty mismatch throws
`PreferredProviderSpecialtyMismatchException`. `newEarliestDate > newLatestDate` throws
`InvalidWaitlistDateRangeException`. Null time preference becomes ANY. A null preferred
Provider clears an existing preference.

**State and audit.** The method changes only earliest date, latest date, preferred Provider,
and time preference. It writes one `WaitlistEntry`/`UPDATE`/null audit. `oldValues` and
`newValues` are JSON objects with exact keys `earliestAppointmentDate`,
`latestAppointmentDate`, `preferredProviderId`, and `preferredTimeOfDay`. Actor selection is
SYSTEM/null or USER/supplied ID. Jackson serialization failure becomes
`IllegalStateException("Failed to serialize waitlist entry audit values", cause)` and rolls
back the update.

**Tests.** `WaitlistEntryModificationServiceTest` has 11 tests covering full update and
before/after audit, inactive anchor/entry, missing entry, preferred Provider outcomes,
preference clearing, invalid dates, null-to-ANY, and both actor modes.

## W9 - Waitlist entry removal

**Implementation.** `WaitlistEntryRemovalService.removeWaitlistEntry(Long
waitlistEntryId, Long actorUserId)` runs under `READ_COMMITTED`.

**Lock and validation order.** It locks the entry using
`WaitlistEntryRepository.findByIdForUpdate(id)`; absence throws
`WaitlistEntryNotFoundException`, and any status other than ACTIVE throws
`WaitlistEntryNotActiveException`. It then locks OFFERED child offers in ascending ID order
using `SlotOfferRepository.findByWaitlistEntryIdAndStatusForUpdate(id, OFFERED)`.

**State and audit.** Entry `ACTIVE -> REMOVED` gets
`WaitlistEntry`/`REMOVE`/null. Each selected offer `OFFERED -> CANCELLED` gets
`SlotOffer`/`CANCEL`/`ENTRY_REMOVED`. ACCEPTED, DECLINED, EXPIRED, and already CANCELLED
offers are not selected or audited. RecoveryJob is untouched. All audits use SYSTEM/null
when `actorUserId` is null and USER/the supplied ID otherwise.

**Tests.** `WaitlistEntryRemovalServiceTest` has 8 tests for multiple/no/mixed offers,
REMOVED and FULFILLED rejection, missing ID, and both actor modes.

## W10 - Appointment completion

**Implementation.** `AppointmentCompletionService.completeAppointment(Long appointmentId,
Long actorUserId)` runs under `READ_COMMITTED`.

**Lock and validation.** `AppointmentRepository.findByIdForUpdate(id)` locks the
Appointment; absence throws `AppointmentNotFoundException`. Non-SCHEDULED status throws
`AppointmentNotScheduledException`. The start must be strictly before `Instant.now()`; a
future start or a start exactly equal to the sampled current instant throws
`AppointmentNotYetStartedException`.

**State and audit.** It calls anchored reconciliation with reason
`APPOINTMENT_COMPLETED`, producing `WaitlistEntry`/`REMOVE`/null and
`SlotOffer`/`CANCEL`/`APPOINTMENT_COMPLETED` rows for selected children. It changes the
Appointment `SCHEDULED -> COMPLETED` and writes `Appointment`/`COMPLETED`/null. Every audit
uses SYSTEM/null or USER/supplied ID. It never creates a RecoveryJob because completion
consumed the appointment capacity.

**Tests.** `AppointmentCompletionServiceTest` has 8 tests for simple completion, two-entry
reconciliation, OFFERED-only cancellation, wrong status, future time, missing ID, actor
consistency, and unchanged RecoveryJob count.

## W11 - Appointment no-show

**Implementation.** `AppointmentNoShowService.markNoShow(Long appointmentId, Long
actorUserId)` runs under `READ_COMMITTED`. The method contract requires a non-null
receptionist actor, but the service does not perform a Java null check.

**Lock and validation.** `AppointmentRepository.findByIdForUpdate(id)` locks the row;
absence throws `AppointmentNotFoundException`, and non-SCHEDULED status throws
`AppointmentNotScheduledException`. There is deliberately no start-time check, so a future
SCHEDULED appointment can be marked no-show.

**State and audit.** Reconciliation uses the exact reason `APPOINTMENT_NOSHOW` with no
underscore between `NO` and `SHOW`. Selected rows receive
`WaitlistEntry`/`REMOVE`/null and `SlotOffer`/`CANCEL`/`APPOINTMENT_NOSHOW`. The Appointment
changes `SCHEDULED -> NO_SHOW` and gets `Appointment`/`NO_SHOW`/null. All rows use
`actorType=USER` and `actorUserId=<supplied actor>`. Passing null would make these USER
audits violate `audit_log_actor_valid` and roll back at flush/commit. No RecoveryJob is
created because capacity was consumed.

**Tests.** `AppointmentNoShowServiceTest` has 8 tests for simple no-show, full
reconciliation, OFFERED-only cancellation, wrong status, missing ID, explicit future-time
success, USER actor propagation, and unchanged RecoveryJob count.

## W12 - Recovery worker offer generation

**Implementation and transaction scope.**
`RecoveryWorkerService.attemptRecovery()` in
`src/main/java/com/recoverysystem/service/RecoveryWorkerService.java` runs one
`READ_COMMITTED` transaction and returns `RecoveryWorkerOutcome`. It attempts one RecoveryJob
and at most one candidate. `RecoveryJobEligibilityClassifier`, `RecoveryCandidateSelector`,
and `RecoveryCandidateRevalidator` do not open transactions or acquire locks themselves;
they run inside the worker's transaction.

**Discovery, routing, and lock order.**

1. `RecoveryJobRepository.findOldestOpenJobIdsWithoutOfferedOffer(PageRequest.of(0, 1))`
   performs an unlocked query for the oldest-created OPEN job having no OFFERED SlotOffer.
2. Ordinary `findById(...)` reads obtain the job's source Appointment and the released
   provider, type, start, and end. A disappearing row throws
   `RecoveryJobNotFoundException` or `AppointmentNotFoundException`.
3. `ProviderRepository.findByIdForUpdate(releasedProviderId)` locks the Provider; absence
   throws `ProviderNotFoundException`.
4. `RecoveryJobRepository.findByIdForUpdate(jobId)` locks the job; absence throws
   `RecoveryJobNotFoundException`.
5. After confirming the locked job is OPEN,
   `SlotOfferRepository.existsByRecoveryJobIdAndStatus(jobId, SlotOfferStatus.OFFERED)`
   performs a plain existence read. It deliberately has no `@Lock`, so no SlotOffer lock is
   acquired before the selected WaitlistEntry lock. If it finds an OFFERED row, the worker
   returns `OFFER_ALREADY_EXISTS_FOR_JOB`; the job stays OPEN and no audit is written.
6. If a candidate is selected, `WaitlistEntryRepository.findByIdForUpdate(candidateId)`
   locks that one entry; absence throws `WaitlistEntryNotFoundException`.

The service does not lock SlotOffer before inserting a new one. The partial unique index
`one_offered_per_recovery` remains the final database defense against two OFFERED rows for
one job. The post-lock existence check turns the known two-worker race into a deliberate
outcome before candidate classification or insertion. The read is safe for compliant offer
creators because they acquire the same RecoveryJob lock before creating an offer.

**Eligibility classification, in exact precedence order.** While Provider and RecoveryJob
are locked, `RecoveryJobEligibilityClassifier.classifyAndHandle(...)` performs:

1. Provider occupancy: if
   `AppointmentRepository.findScheduledOverlappingIds(providerId, start, end)` is nonempty,
   set job `OPEN -> FILLED`, set microsecond-truncated `filledAt`, write
   `RecoveryJob`/`FILLED`/`INTERVAL_ALREADY_OCCUPIED` with SYSTEM/null, and stop.
2. ACTIVE block: if
   `ProviderUnavailabilityRepository.existsOverlappingActiveBlock(...)` is true, set job
   `OPEN -> SUPPRESSED`, set `suppressionReason='PROVIDER_BLOCK_ACTIVATED'`, write
   `RecoveryJob`/`SUPPRESS`/null with SYSTEM/null, and stop.
3. PENDING block: if
   `ProviderUnavailabilityRepository.existsOverlappingPendingBlock(...)` is true, leave the
   OPEN job and all audits unchanged, and stop for this attempt.
4. Lead time: load the first `SchedulingPolicy` row and calculate
   `Instant.now() + minimumRecoveryLeadMinutes`. If released start is strictly before that
   threshold, set job `OPEN -> SUPPRESSED`, set
   `suppressionReason='LEAD_TIME_CLOSED'`, write `RecoveryJob`/`SUPPRESS`/null with
   SYSTEM/null, and stop. Equality is eligible because the code uses `isBefore`.
5. If none applies, leave job OPEN and proceed without a classifier audit.

The migration inserts one policy row, but no database constraint enforces exactly one. A
missing row makes `findAll().getFirst()` throw `NoSuchElementException`; multiple rows make
the first repository result effective without explicit ordering.

**Candidate pre-filter.** `RecoveryCandidateSelector.selectTopCandidate(...)` converts the
released start to the configured clinic ZoneId. It calls
`WaitlistEntryRepository.findRankedEligibleCandidateIds(...)` with page size one. An entry
is included only when all conditions hold:

1. entry status is ACTIVE;
2. entry AppointmentType equals the released AppointmentType;
3. released clinic-local date is between earliest and latest dates, inclusive;
4. an anchor Appointment exists at `currentAppointmentId`, is SCHEDULED, and has
   `anchor.patientId = entry.patientId`;
5. `releasedEndAt <= anchor.startAt`; therefore the released appointment must finish no
   later than the candidate's current appointment begins;
6. the patient has no SCHEDULED Appointment satisfying
   `conflict.startAt < releasedEndAt && conflict.endAt > releasedStartAt`;
7. no SlotOffer of any status exists for this RecoveryJob through any WaitlistEntry owned
   by the same patient. This is historical exclusion per patient and job, not only per
   WaitlistEntry and not only for OFFERED status.

**Clinic-local ranking.** Eligible rows are ordered by:

1. `WaitlistEntry.createdAt ASC` for FIFO;
2. time preference rank: ANY and an exact MORNING/AFTERNOON match rank 0, while a mismatch
   ranks 1. A released local start before 12:00 is MORNING; 12:00 or later is AFTERNOON;
3. provider preference rank: null and an exact released-provider match rank 0, while a
   different preferred Provider ranks 1;
4. `WaitlistEntry.id ASC` as the final deterministic tie-breaker.

Because creation time precedes preference ranks, preferences break a created-at tie; they
do not let a newer entry pass an older one.

**Locked candidate revalidation.** `RecoveryCandidateRevalidator.isStillEligible(...)`
rechecks ACTIVE status, exact AppointmentType, inclusive local date, anchor existence,
SCHEDULED anchor status, anchor/entry patient equality, `releasedEndAt <= anchor.startAt`,
absence of SCHEDULED patient overlap through
`AppointmentRepository.findScheduledOverlappingIdsForPatient(...)`, and absence of any
prior offer for that patient/job through
`SlotOfferRepository.existsPriorOfferForPatientAndJob(...)`. The anchor and the two final
queries are unlocked reads executed after the WaitlistEntry lock. There is no comparison to
an external expected anchor or patient because no such identity is passed to this helper.

**All nine current outcomes.** The request that produced this handoff called these "seven
terminal/proceed outcomes," but `RecoveryWorkerOutcome` contains nine values and all are
documented:

1. `NO_OPEN_JOBS`: discovery returns no eligible job, or the selected locked job is no
   longer OPEN. No row or audit changes.
2. `OFFER_ALREADY_EXISTS_FOR_JOB`: after the RecoveryJob lock is acquired and OPEN status
   is confirmed, the plain SlotOffer existence check finds an OFFERED row for that job. The
   job stays OPEN; no candidate is classified, no row changes, and no audit is written.
3. `RELEASED_INTERVAL_OCCUPIED`: classifier finds a scheduled provider overlap. Job becomes
   FILLED with `filledAt`; audit is
   `RecoveryJob`/`FILLED`/`INTERVAL_ALREADY_OCCUPIED`, SYSTEM/null.
4. `PROVIDER_ACTIVELY_BLOCKED`: classifier finds ACTIVE unavailability. Job becomes
   SUPPRESSED with `suppressionReason='PROVIDER_BLOCK_ACTIVATED'`; audit is
   `RecoveryJob`/`SUPPRESS`/null, SYSTEM/null.
5. `PROVIDER_PENDING_BLOCKED`: classifier finds PENDING unavailability after no occupancy
   or ACTIVE block. Job stays OPEN; there is no audit and no offer.
6. `LEAD_TIME_CLOSED`: released start is before the policy threshold. Job becomes
   SUPPRESSED with `suppressionReason='LEAD_TIME_CLOSED'`; audit is
   `RecoveryJob`/`SUPPRESS`/null, SYSTEM/null.
7. `NO_ELIGIBLE_CANDIDATE`: selector returns no ID. Job becomes EXHAUSTED; audit is
   `RecoveryJob`/`EXHAUST`/null, SYSTEM/null.
8. `CANDIDATE_BECAME_STALE`: the selected row is locked but any revalidation condition is
   false. Job stays OPEN, no offer is inserted, and no audit is written. The worker does
   not try a second candidate in this transaction.
9. `OFFER_CREATED`: revalidation passes. The service loads the first SchedulingPolicy,
   inserts one SlotOffer with status OFFERED and
   `expiresAt=Instant.now()+offerDurationMinutes`, writes
   `SlotOffer`/`CREATE`/null with SYSTEM/null, and leaves the RecoveryJob OPEN.

If the classifier returns false but leaves a job state/reason that matches none of FILLED,
SUPPRESSED/`PROVIDER_BLOCK_ACTIVATED`, SUPPRESSED/`LEAD_TIME_CLOSED`, or OPEN,
`outcomeAfterClassification(...)` throws `IllegalStateException` rather than inventing an
outcome.

**Tests.** `RecoveryWorkerServiceTest` has 12 tests for no job, every classifier branch,
no candidate, offer duration, top candidate, oldest job, routing around an offered job,
the post-lock `OFFER_ALREADY_EXISTS_FOR_JOB` branch, and a true two-worker race that produces
exactly one `OFFER_CREATED`, one `OFFER_ALREADY_EXISTS_FOR_JOB`, and one SlotOffer row.
`RecoveryJobEligibilityClassifierTest` has 7 tests for precedence and each block,
occupancy, lead, eligible, and CANCELLED-block condition. `RecoveryCandidateSelectorTest`
has 15 tests for every filter and rank, including isolation of the strict
`releasedEndAt <= anchor.startAt` rule from patient-overlap exclusion.
`RecoveryCandidateRevalidatorTest` has 16 tests for every recheck, historical prior-offer
scope, missing/mismatched anchor, and no side effects. Candidate-mutation interleavings are
still not tested concurrently.

## W13A - Scheduler accepts on behalf

**Current implementation boundary.** There is no class or method named specifically for
W13A. `OfferAcceptanceOrchestrator.acceptOffer(slotOfferId, patientId, actorUserId)` can
represent the transaction mechanics because the target patient and audit actor are separate
arguments. Passing a non-null receptionist user ID records USER/the receptionist on every
W4 request-actor audit while acceptance moves the supplied patient.

**Transaction and locks.** The orchestrator performs an unlocked SlotOffer routing read;
absence throws `SlotOfferNotFoundException`. The delegated `acceptOfferTransaction(...)`
uses `READ_COMMITTED` with no rollback for `OfferExpiredException` and
`ProviderIntervalOccupiedException`. It performs unlocked routing reads for SlotOffer,
RecoveryJob, WaitlistEntry, old Appointment, and offered source Appointment. It then locks
old/offered Providers in deduplicated ascending ID order with
`ProviderRepository.findAllByIdInForUpdate(...)`, the old Appointment with
`AppointmentRepository.findByIdForUpdate(...)`, the RecoveryJob with
`RecoveryJobRepository.findByIdForUpdate(...)`, ACTIVE anchored entries in ascending ID
order with `WaitlistEntryRepository.findByCurrentAppointmentIdAndStatusForUpdate(...)`, and
the accepted plus cleanup offers in ascending ID order with
`SlotOfferRepository.findAllByIdInForUpdate(...)`.

**Validations and exceptions.** Missing routed or locked rows throw the reusable
`SlotOfferNotFoundException`, `RecoveryJobNotFoundException`,
`WaitlistEntryNotFoundException`, `AppointmentNotFoundException`, or
`ProviderNotFoundException` for the row involved. ACTIVE or PENDING destination
unavailability throws `ProviderUnavailableException`. A non-SCHEDULED old Appointment
throws `AppointmentNotScheduledException`; patient mismatch before or after the entry lock
throws `OfferAcceptanceOwnershipException`; a non-OPEN job throws
`RecoveryJobNotOpenException`; absence from the ACTIVE entry set throws
`WaitlistEntryNotActiveException`; and any fulfilled sibling produces
`SiblingAlreadyFulfilledException` with sorted sibling IDs.

The accepted SlotOffer has six exact state/time outcomes: ACCEPTED throws
`OfferAlreadyAcceptedException`; DECLINED throws `OfferAlreadyResolvedException` containing
DECLINED; CANCELLED throws `OfferAlreadyResolvedException` containing CANCELLED; EXPIRED
throws `OfferExpiredException` without a new audit; stale OFFERED with
`expiresAt <= Instant.now()` changes to EXPIRED, writes SYSTEM/null
`SlotOffer`/`EXPIRE`/null, commits because of `noRollbackFor`, and throws
`OfferExpiredException`; unexpired OFFERED with `expiresAt > Instant.now()` proceeds.

**Cleanup and successful state.** Category A is the accepted offer. Category B contains
other OFFERED offers for its entry. Category C contains OFFERED offers for ACTIVE siblings
anchored to the same old Appointment. Category D contains OFFERED offers for other ACTIVE
entries of the same patient, excluding the old Appointment anchor, where their RecoveryJob
source interval overlaps the destination. The service locks the deduplicated ascending ID
set and changes every non-accepted row still OFFERED to CANCELLED with
`SlotOffer`/`CANCEL`/`SUPERSEDED_BY_ACCEPTANCE`, USER/the receptionist actor ID.

If the destination provider is already occupied, the accepted offer becomes CANCELLED with
`SlotOffer`/`CANCEL`/`PROVIDER_INTERVAL_OCCUPIED`; the accepted RecoveryJob becomes FILLED
with `RecoveryJob`/`FILLED`/null; those changes and Category cleanup commit; and
`ProviderIntervalOccupiedException` is thrown. Otherwise the service inserts the patient's
new SCHEDULED Appointment and writes `Appointment`/`CREATE`/null; changes the old
Appointment to CANCELLED with reason RESCHEDULED and replacement ID and writes
`Appointment`/`CANCEL`/null; changes the accepted entry to FULFILLED and writes
`WaitlistEntry`/`FULFILL`/null; changes ACTIVE siblings to REMOVED and writes
`WaitlistEntry`/`REMOVE`/null for each; changes the accepted offer to ACCEPTED with
`acceptedAt` and writes `SlotOffer`/`ACCEPT`/null; and changes the accepted job to FILLED
with `filledAt` and writes `RecoveryJob`/`FILLED`/null. If the old interval has no ACTIVE
block, it also inserts an OPEN RecoveryJob and writes `RecoveryJob`/`CREATE`/null; PENDING
does not stop that insert. All request-actor audits on this path use USER/the receptionist
ID.

An appointment `no_provider_overlap` violation becomes `ProviderDoubleBookedException` and
rolls back. A `no_patient_overlap` violation becomes `PatientDoubleBookedException`, rolls
back the entire main transaction, and then triggers
`OfferAcceptancePatientConflictCleanup.cleanupAfterPatientConflict(...)` in a separate
`REQUIRES_NEW` transaction. That cleanup locks RecoveryJob then SlotOffer, preserves the
job, and, only if the offer is still OFFERED, changes it to CANCELLED with
`SlotOffer`/`CANCEL`/`PATIENT_SCHEDULE_CONFLICT`, USER/the receptionist ID. Any other
appointment integrity failure remains `DataIntegrityViolationException` and rolls back.

**Missing authorization.** The service does not load a `User`, check that `actorUserId` has
role RECEPTIONIST, or establish that the actor may act for `patientId`. Those checks require
the future session/security and REST boundary.

**Status and tests.** Reusable transaction mechanics exist.
`OfferAcceptanceOnBehalfTest.receptionistCanAcceptOfferForThePatientWhoOwnsIt` passes the
entry owner's patient ID with a different RECEPTIONIST user's ID as actor. It proves the
new Appointment belongs to the patient and all six audit rows created after the baseline
use USER/the receptionist ID. The same class's
`receptionistCannotAcceptOfferForPatientWhoDoesNotOwnIt` proves a different patient ID still
throws `OfferAcceptanceOwnershipException`, leaves Appointment, WaitlistEntry, SlotOffer,
and RecoveryJob state unchanged, and creates no audit after the baseline. The missing
REST/security boundary means W13A must not be reported as a complete authorized workflow.

## W13B - Scheduler reassigns to another patient

**Current status.** No production service accepts an old Appointment and a different target
patient for scheduler reassignment. No repository orchestration, audit contract, exception
set, or test class exists for W13B.

`AppointmentReschedulingService.rescheduleAppointment(...)` is not a W13B substitute: it
always copies `patientId` from the old routing Appointment into the replacement and exposes
no target-patient argument. Therefore there is no current lock sequence, state transition,
audit behavior, or tested rejection outcome to document for W13B. Its design and
implementation remain Phase 1 follow-up work.

## W14 - Provider-block request and activation

### W14 request/creation

**Implementation.** `ProviderBlockCreationService.createProviderBlock(Long providerId,
Instant startAt, Instant endAt, String reason, Long actorUserId)` runs under
`READ_COMMITTED`.

**Lock and validation.** It first locks Provider with
`ProviderRepository.findByIdForUpdate(providerId)`; absence throws
`ProviderNotFoundException`. `startAt` must be strictly before `endAt`, otherwise
`InvalidBlockIntervalException` is thrown. While the Provider remains locked,
`AppointmentRepository.findScheduledOverlappingIds(providerId, startAt, endAt)` finds
scheduled conflicts in ascending Appointment ID order.

**Conflict branch: create PENDING.** If conflict IDs are nonempty:

1. insert ProviderUnavailability with status PENDING, the supplied interval/reason, and
   `activatedAt=null`;
2. do not suppress a RecoveryJob and do not cancel a SlotOffer;
3. write one audit with `entityType='ProviderUnavailability'`, `action='CREATE'`,
   `reason='AWAITING_CONFLICT_RESOLUTION'`, and `newValues` JSON containing the exact key
   `conflictingAppointmentIds` with the query result IDs.

**No-conflict branch: create ACTIVE.** If no conflict ID exists:

1. insert ProviderUnavailability with status ACTIVE and microsecond-truncated
   `activatedAt=Instant.now()`;
2. call `RecoveryJobSuppressionCascade.suppressAffectedRecoveryJobs(...)`;
3. write `ProviderUnavailability`/`CREATE`/null for the new block.

The suppression cascade locks all overlapping OPEN jobs through
`RecoveryJobRepository.findOpenOverlappingByProviderForUpdate(...)`, ordered by job ID. It
then collects those job IDs and locks their OFFERED offers through
`SlotOfferRepository.findOfferedByRecoveryJobIdsForUpdate(...)`, ordered by offer ID. Each
job changes `OPEN -> SUPPRESSED`, receives
`suppressionReason='PROVIDER_BLOCK_ACTIVATED'`, and gets
`RecoveryJob`/`SUPPRESS`/null. Each selected offer changes `OFFERED -> CANCELLED` and gets
`SlotOffer`/`CANCEL`/`RECOVERY_JOB_SUPPRESSED`. Jobs not OPEN, non-overlapping jobs, and
non-OFFERED offers are not selected or audited.

The request and all cascade rows use SYSTEM/null when `actorUserId` is null and USER/the
supplied ID otherwise. Provider-block audit JSON serialization failure becomes
`IllegalStateException("Failed to serialize provider block audit values", cause)` and rolls
back creation.

**Tests.** `ProviderBlockCreationServiceTest` has 9 tests for ACTIVE creation, suppression
and offer cancellation, mixed offer states, non-overlap, already-suppressed jobs, PENDING
creation, missing Provider, invalid interval, and actor attribution.

### W14 activation

**Implementation.** `ProviderBlockActivationService.activateProviderBlock(Long
providerUnavailabilityId, Long actorUserId)` runs under `READ_COMMITTED`.

**Routing and complete lock order.** An unlocked
`ProviderUnavailabilityRepository.findById(id)` supplies `providerId`; absence throws
`ProviderUnavailabilityNotFoundException`. Then:

1. `ProviderRepository.findByIdForUpdate(providerId)` locks Provider; absence throws
   `ProviderNotFoundException`.
2. `ProviderUnavailabilityRepository.findByIdForUpdate(id)` locks the block; disappearance
   throws `ProviderUnavailabilityNotFoundException`.
3. After validation and state mutation, the suppression cascade locks overlapping OPEN
   RecoveryJobs in ascending ID order with
   `RecoveryJobRepository.findOpenOverlappingByProviderForUpdate(...)`.
4. It locks those jobs' OFFERED SlotOffers in ascending ID order with
   `SlotOfferRepository.findOfferedByRecoveryJobIdsForUpdate(...)`.

**Validation and rejection outcomes.** A locked status other than PENDING, including ACTIVE
or CANCELLED, throws `ProviderBlockNotPendingException` containing the actual status.
`AppointmentRepository.findScheduledOverlappingIds(...)` reruns the conflict query; a
nonempty result throws `ProviderBlockConflictsUnresolvedException` containing block,
provider, and conflicting Appointment IDs. Rejection leaves the block, recovery jobs,
offers, and audits unchanged.

**Success state and audits.** The block changes `PENDING -> ACTIVE` and gets a
microsecond-truncated `activatedAt`. The suppression cascade applies the exact job/offer
changes and audits stated in the creation no-conflict branch:
`RecoveryJob`/`SUPPRESS`/null with suppression reason stored on the job as
`PROVIDER_BLOCK_ACTIVATED`, and
`SlotOffer`/`CANCEL`/`RECOVERY_JOB_SUPPRESSED`. The block receives
`ProviderUnavailability`/`ACTIVATE`/null. Every row uses SYSTEM/null or USER/supplied ID.

**Missing transition.** The enum and migration permit CANCELLED, and the older design calls
for PENDING to CANCELLED by update rather than delete. No current service implements that
transition, sets `cancelledAt`, defines its audit action/reason, or tests it.

**Tests.** `ProviderBlockActivationServiceTest` has 7 tests for simple activation,
activation with job/offer cascade, unresolved conflict, missing block, ACTIVE and CANCELLED
rejection, and actor propagation across the cascade.
