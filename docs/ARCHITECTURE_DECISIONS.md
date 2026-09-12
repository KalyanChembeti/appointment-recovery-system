# Architecture Decisions

This file records decisions that are visible in the current repository. Each decision
states the reason, the concrete implementation location, and the invariant or risk it is
intended to address. Planned choices are labeled as planned when supporting code is absent.

## 1. Keep the MVP as a modular monolith

**Decision.** The backend is one Spring Boot application with domain, repository, service,
exception, and future web/security package boundaries. It is packaged as one JAR.

**Reason.** Booking, waitlist, recovery, and audit changes frequently need one PostgreSQL
transaction. Keeping those modules in one process permits local method calls and one
transaction manager while retaining understandable package boundaries.

**Where implemented.** `pom.xml` declares one `appointment-recovery-system` artifact.
`src/main/java/com/recoverysystem/AppointmentRecoverySystemApplication.java` is the only
application entry point. All transactional behavior is under
`src/main/java/com/recoverysystem/service/`.

**Risks/invariants protected.** One transaction can atomically change appointments,
waitlist entries, offers, recovery jobs, and audit rows. The decision avoids distributed
transaction and message-delivery failure modes in the MVP. Package boundaries are not
process isolation; future code must still keep transaction entry points clear.

## 2. Use Java 21, Spring Boot 3.3.4, PostgreSQL 15, and Flyway

**Decision.** Java 21 and Spring Boot 3.3.4 provide the application runtime. PostgreSQL 15
provides persistence and constraint enforcement. Flyway 10.10.0 applies versioned SQL; the
resolved PostgreSQL JDBC driver is 42.7.4.

**Reason.** The system relies on PostgreSQL range/exclusion behavior and row locks, so an
in-memory substitute cannot define production semantics. Java 21 supplies the language and
runtime baseline used by every compiled class.

**Where implemented.** Versions and dependencies are in `pom.xml`; `docker-compose.yml`
and every Testcontainers declaration use `postgres:15`; migrations are in
`src/main/resources/db/migration/`.

**Risks/invariants protected.** Tests exercise the same database features used in
production, including `btree_gist`, partial indexes, `TIMESTAMPTZ`, and pessimistic locks.
Upgrading Spring Boot, Hibernate, Flyway, Java, or PostgreSQL requires a full migration and
transaction test run.

## 3. Make Flyway the schema authority and keep Hibernate validate-only

**Decision.** SQL migrations create and evolve the schema. Hibernate validates mappings
and must not generate or update DDL.

**Reason.** Exclusion constraints, partial unique indexes, `jsonb`, identity columns, and
explicit `CHECK` constraints need reviewed PostgreSQL SQL. Automatic ORM DDL would not be a
reliable representation of these invariants.

**Where implemented.** `src/main/resources/application.yml` sets
`spring.flyway.enabled: true`, `spring.flyway.locations: classpath:db/migration`, and
`spring.jpa.hibernate.ddl-auto: validate`. SQL lives in
`V1__reference_tables.sql` and `V2__core_workflow_tables.sql`.

**Risks/invariants protected.** Application startup fails when entity mappings drift from
the migrated schema. Database changes remain ordered and reviewable. A developer must never
switch `ddl-auto` to `create`, `update`, or `create-drop` as a substitute for a migration.

## 4. Plan server-side sessions; do not use JWT for the application session

**Decision.** The intended authentication boundary is a server-side session whose identity
and role are resolved before a workflow service is called. JWT is not the planned MVP
session mechanism.

**Reason.** Server-side sessions permit immediate logout/revocation and keep authorization
state under server control. Workflow services already accept a patient ID and/or actor user
ID, so a future web layer can derive those values from authenticated session state rather
than trusting arbitrary request fields.

**Where implemented.** This remains partly planned. `pom.xml` includes
`spring-boot-starter-security`, and `src/main/resources/application.yml` contains session
cookie and JDBC-store properties. There is no Spring Session JDBC dependency, custom
`SecurityFilterChain`, login endpoint, role mapping, or controller code yet.

**Risks/invariants protected.** Once implemented, the web boundary must prevent a caller
from choosing another user's `actorUserId` or patient identity. Until then, service methods
do not prove authentication or role authorization. `OfferAcceptanceOnBehalfTest` proves
that the reusable transaction keeps patient ownership separate from receptionist audit
attribution and still rejects a wrong patient ID, but W13A cannot be considered a complete
receptionist workflow without the authenticated boundary.

## 5. Do not add Kafka, Redis, Kubernetes, or microservices to the MVP

**Decision.** PostgreSQL tables and scheduled in-process workers are the intended MVP
coordination mechanism. No Kafka, Redis, Kubernetes, or service decomposition is present.

**Reason.** `RecoveryJob` and `SlotOffer` already provide durable work and offer state.
Explicit database transactions and constraints solve the current consistency problem
without a second consistency system.

**Where implemented.** `pom.xml` has no dependencies for those technologies.
`docker-compose.yml` defines only PostgreSQL. Recovery discovery is implemented by
`RecoveryJobRepository.findOldestOpenJobIdsWithoutOfferedOffer(...)`, and expiry discovery
is implemented by `SlotOfferRepository.findStaleOfferedIds(...)`.

**Risks/invariants protected.** This avoids dual writes between a database and broker or
cache. Worker throughput is bounded by database polling and the current one-attempt design;
load testing is required before making capacity claims.

## 6. Store timestamps in UTC and interpret business dates in an IANA clinic zone

**Decision.** Persist instants in PostgreSQL `TIMESTAMPTZ` and use UTC for Hibernate JDBC.
Convert to the configured IANA clinic zone only for clinic-calendar decisions.

**Reason.** Instants remain unambiguous through daylight-saving changes. Provider schedule
days/times, waitlist date windows, and morning/afternoon preferences are local business
concepts and therefore need the clinic zone.

**Where implemented.** Both migrations use `TIMESTAMPTZ` for timestamps.
`application.yml` sets `hibernate.jdbc.time_zone: UTC` and
`recovery-system.clinic.timezone: America/New_York`.
`AppointmentBookingEligibilityValidator` converts appointment intervals before comparing
`ProviderSchedule`; `RecoveryCandidateSelector` converts the released start before ranking;
`RecoveryWorkerService` converts it before candidate revalidation.

**Risks/invariants protected.** The same instant is stored consistently regardless of host
timezone, while local-day and time-preference behavior remains clinic-specific.
`AppointmentBookingEligibilityValidator` deliberately rejects a derived interval crossing
a clinic-local midnight because cross-day schedule matching is not specified.

## 7. Do not model a pre-generated `AppointmentSlot`

**Decision.** Availability is calculated from provider schedules, unavailability, and
existing appointments. There is no `AppointmentSlot` table or entity.

**Reason.** A slot inventory would duplicate derived capacity and introduce a second state
that must be synchronized with schedule changes, blocks, bookings, cancellations, and
reschedules.

**Where implemented.** `ProviderScheduleRepository.findActiveByProviderIdAndDayOfWeek(...)`
provides working hours; `ProviderUnavailabilityRepository` supplies block checks; the
`appointment` exclusion constraints and appointment queries supply occupied intervals.
Neither migration and no class defines `AppointmentSlot`.

**Risks/invariants protected.** Capacity cannot drift because a generated slot was not
updated. Query cost is paid at decision time, and the final appointment constraints remain
the authority when two requests race.

## 8. Use `RecoveryJob` as the durable identity of released capacity

**Decision.** A cancellation or replacement that releases usable capacity creates one
`OPEN` `RecoveryJob` linked to the source appointment. The worker changes that durable row
to `FILLED`, `SUPPRESSED`, or `EXHAUSTED`, or leaves it `OPEN` while an offer is active or a
candidate became stale.

**Reason.** The source appointment gives the released provider, type, start, and end. A
durable job supports retry and inspection without reconstructing work from event history or
holding it only in memory.

**Where implemented.** The table and unique `source_appointment_id` are in
`V2__core_workflow_tables.sql`. W2 `AppointmentCancellationService`, W3
`AppointmentReschedulingService`, and W4 `OfferAcceptanceService` create jobs when the old
interval has no overlapping `ACTIVE` block. W12 `RecoveryWorkerService` and
`RecoveryJobEligibilityClassifier` process them. W14 `RecoveryJobSuppressionCascade`
suppresses affected open jobs.

**Risks/invariants protected.** The unique source foreign key prevents duplicate recovery
identity for one released appointment. The job's status records why work ended. An
overlapping `PENDING` provider block does not terminally suppress the job; an overlapping
`ACTIVE` block does.

## 9. Give provider unavailability three persistent states

**Decision.** `ProviderUnavailabilityStatus` contains `PENDING`, `ACTIVE`, and `CANCELLED`.
Both `PENDING` and `ACTIVE` block new destination bookings. Only `ACTIVE` suppresses
overlapping `OPEN` recovery jobs and cancels their `OFFERED` offers. `CANCELLED` does not
block availability.

**Reason.** A block request may overlap existing scheduled appointments. `PENDING` reserves
the interval against new consumption while staff resolve those appointments, without
destroying potentially recoverable work. `ACTIVE` means the block is final. `CANCELLED`
retains history without affecting capacity.

**Where implemented.** The status constraint is in `V2__core_workflow_tables.sql`.
`AppointmentBookingEligibilityValidator` checks ACTIVE or PENDING.
`RecoveryJobEligibilityClassifier` handles ACTIVE and PENDING separately.
`ProviderBlockCreationService` chooses PENDING when scheduled conflicts exist and ACTIVE
otherwise. `ProviderBlockActivationService` implements PENDING to ACTIVE.
`ProviderBlockCancellationService` implements PENDING to CANCELLED, sets `cancelledAt`,
preserves the block's original reason, and writes one `ProviderUnavailability`/`CANCEL`
audit whose reason is the optional cancellation explanation.

**Risks/invariants protected.** New appointments cannot slip into an interval under
resolution. Recovery jobs are not prematurely terminal under PENDING. Cancellation retains
the block row for history and makes it stop blocking future destination bookings. ACTIVE
blocks cannot be cancelled because no mechanism reverses their SUPPRESSED RecoveryJobs or
CANCELLED SlotOffers.

## 10. Enforce scheduled patient and provider overlap in PostgreSQL

**Decision.** PostgreSQL exclusion constraints reject overlapping half-open time ranges for
the same patient or provider when both rows have status `SCHEDULED`.

**Reason.** Application prechecks cannot close every race. A database constraint evaluates
the final write against concurrent committed or waiting writes and protects the invariant
regardless of which service inserts the appointment.

**Where implemented.** `V2__core_workflow_tables.sql` creates `no_patient_overlap` and
`no_provider_overlap` with `EXCLUDE USING gist`, equality on the ID, and overlap on
`tstzrange(start_at, end_at, '[)')`, partial to `status = 'SCHEDULED'`.
`BookingConstraintViolationTranslator.translate(...)` maps those constraint names to
`PatientDoubleBookedException` and `ProviderDoubleBookedException` in W1, W3, and W4.

**Risks/invariants protected.** A patient or provider cannot hold overlapping scheduled
appointments. Adjacent intervals are legal because `[start, end)` does not overlap a range
that starts exactly at `end`. Unknown integrity violations are returned unchanged rather
than mislabeled.

## 11. Allow at most one current OFFERED slot offer per recovery job

**Decision.** The database permits many historical offers for a job but at most one whose
status is `OFFERED`.

**Reason.** Declined, expired, cancelled, and accepted offers must remain auditable. A
partial unique index preserves history while preventing two concurrently actionable offers
for one released interval.

**Where implemented.** `V2__core_workflow_tables.sql` creates
`one_offered_per_recovery` on `slot_offer(recovery_job_id) WHERE status = 'OFFERED'`.
W12 discovery also excludes jobs with an offered row through
`RecoveryJobRepository.findOldestOpenJobIdsWithoutOfferedOffer(...)`. After locking the
RecoveryJob and confirming it is OPEN, W12 calls the non-locking
`SlotOfferRepository.existsByRecoveryJobIdAndStatus(jobId, OFFERED)` method and returns
`OFFER_ALREADY_EXISTS_FOR_JOB` if another worker already created the current offer.

**Risks/invariants protected.** Two workers cannot commit two current offers for one job,
even if both saw it as eligible. The application branch is tested by
`RecoveryWorkerServiceTest.concurrentWorkersCreateOneOfferAndReturnHandledRaceOutcome`,
which proves one `OFFER_CREATED`, one `OFFER_ALREADY_EXISTS_FOR_JOB`, and one committed
SlotOffer. The database constraint is also tested directly by `SchemaMigrationTest`.

## 12. Use a canonical lock hierarchy

**Decision.** Transactions that touch multiple mutable aggregate types follow this order:

```text
Provider(s)
  -> ProviderUnavailability (when an existing block is changed)
  -> Appointment(s)
  -> RecoveryJob(s)
  -> WaitlistEntry(s)
  -> SlotOffer(s)
```

A workflow may skip types it does not use. It must not acquire a later type and then lock
an earlier type.

**Reason.** Consistent cross-type order removes known reverse-order wait cycles. Provider
is first because booking and provider-block operations need the same synchronization point.
Parent rows precede mutable dependents so parent state can be revalidated before child
state changes.

**Where implemented.** Pessimistic methods are declared in `ProviderRepository`,
`ProviderUnavailabilityRepository`, `AppointmentRepository`, `RecoveryJobRepository`,
`WaitlistEntryRepository`, and `SlotOfferRepository`. The service-specific sequences are
listed in `docs/CONCURRENCY_AND_LOCKING.md`.

**Risks/invariants protected.** The order reduces deadlock risk and prevents work based on
stale parent state. It does not prove the absence of all PostgreSQL deadlocks, because
constraint evaluation and query execution can take internal locks; true race-matrix tests
remain required.

## 13. Use READ COMMITTED with explicit locks, revalidation, and constraints

**Decision.** Workflow transaction entry points use
`@Transactional(isolation = Isolation.READ_COMMITTED)`. They lock mutable decision rows,
repeat status/ownership/eligibility checks after locks, and rely on database constraints for
write races that prechecks cannot close.

**Reason.** READ COMMITTED avoids treating a whole transaction as a frozen snapshot. Row
locks serialize operations that target known identities. Revalidation turns the state seen
after waiting into the authoritative decision. Constraints protect global overlap and
uniqueness invariants.

**Where implemented.** Transaction annotations are on W1-W5, W7-W12, W14, the W6 per-offer
processor, and the W4 cleanup service. Examples of post-lock checks include appointment
status in W2/W3/W10/W11, recovery-job status and waitlist status in W4, offer status in
W5/W6, block status in W14 activation, and candidate eligibility in W12.

**Risks/invariants protected.** A waiter does not proceed using the state that existed
before another transaction committed. This combination is not claimed to be equivalent to
`SERIALIZABLE`: unlocked discovery queries can observe phantoms, multiple statements can
see different committed states, and PostgreSQL does not produce serialization failures for
the application to retry. Correctness claims are limited to implemented locks,
revalidation, and constraints.

## 14. Acquire same-type multi-row locks in ascending ID order

**Decision.** IDs are deduplicated and sorted before a multi-row lock; locking queries also
use `ORDER BY id ASC`.

**Reason.** Two transactions that need the same row set in different logical orders should
wait in the same physical order instead of each holding one row the other needs.

**Where implemented.** W3 and W4 sort provider IDs before
`ProviderRepository.findAllByIdInForUpdate(...)`. W4 `SlotOfferCleanupDiscovery` returns
deduplicated sorted offer IDs before `SlotOfferRepository.findAllByIdInForUpdate(...)`.
Waitlist, recovery-job, and slot-offer batch lock queries specify ascending IDs in their
repository JPQL.

**Risks/invariants protected.** This reduces same-table deadlock cycles for provider,
recovery-job, waitlist-entry, and slot-offer batches. Callers must preserve the rule when
adding another batch lock method.

## 15. Separate routing reads from authoritative locked reads

**Decision.** A service may read a row without a lock to learn foreign-key IDs needed to
enter the canonical hierarchy. It later locks the authoritative mutable row and rechecks
its current state before mutation.

**Reason.** The parent ID needed for the first lock is often reachable only through the
target row. Locking the target first would reverse the hierarchy. A non-authoritative read
solves routing while the later lock decides whether work can proceed.

**Where implemented.** W2 reads the appointment to obtain `providerId`, locks Provider,
then locks Appointment. W3 reads the old appointment, locks old/new Providers in ascending
ID order, then locks the old Appointment. W4 performs routing reads across SlotOffer,
RecoveryJob, WaitlistEntry, and Appointments, locks Providers first, and then locks the old
Appointment, RecoveryJob, active WaitlistEntries, and SlotOffers. W14 activation reads the
block for `providerId`, locks Provider, then locks the block.

**Risks/invariants protected.** Routing data can become stale while locks are acquired, so
it must never authorize a mutation by itself. Current services revalidate mutable status
and identity after locking where an external expected identity exists. W12 explicitly uses
the selected candidate's own locked identity and therefore has no separate external
current-appointment identity to compare.

## 16. Discover mutable dependent rows after locking the parent

**Decision.** When a transaction will mutate children selected by parent state, it locks
the parent first and then discovers/locks the relevant child rows. Child batch queries use
stable ID order.

**Reason.** Discovering dependents before the parent lock can omit a row added or changed by
a transaction that wins the parent race. Parent-first discovery narrows that window and
places all participating workflows behind the same parent serialization point.

**Where implemented.** `WaitlistReconciliationCascade` is called after an Appointment lock
and then locks ACTIVE WaitlistEntries followed by their OFFERED SlotOffers. W4 locks the old
Appointment and RecoveryJob before it locks anchored active entries and discovers Category
A/B/C/D cleanup offers. W9 locks the WaitlistEntry before it selects its OFFERED offers.
W14 holds Provider and, on activation, the ProviderUnavailability row before
`RecoveryJobSuppressionCascade` locks overlapping OPEN jobs and their OFFERED offers.

**Risks/invariants protected.** The transaction acts on children associated with the
locked parent state and takes child locks in hierarchy order. Rows inserted without first
using the same parent lock can still create phantoms under READ COMMITTED; database
constraints and future race tests remain part of the defense.

## 17. Attempt only one W12 candidate per transaction

**Decision.** One `RecoveryWorkerService.attemptRecovery()` transaction selects at most one
candidate, locks and revalidates that candidate once, then either creates one offer or
returns `CANDIDATE_BECAME_STALE`. It does not loop to a second candidate in the same
transaction.

**Reason.** A short transaction holds fewer locks and avoids changing lock order as it
walks the ranking. A later worker invocation can retry the still-OPEN job against a fresh
ranking.

**Where implemented.** `RecoveryCandidateSelector.selectTopCandidate(...)` uses
`PageRequest.of(0, 1)`. `RecoveryWorkerService.attemptRecovery()` returns immediately after
a failed `RecoveryCandidateRevalidator.isStillEligible(...)` check.

**Risks/invariants protected.** A stale first candidate cannot cause the transaction to
accumulate waitlist locks. Recovery can require another poll, so scheduling and monitoring
must tolerate `CANDIDATE_BECAME_STALE` without treating the job as failed or exhausted.

## 18. Commit aggressive offer expiry before reporting expiry in W4

**Decision.** When acceptance locks an `OFFERED` offer whose `expiresAt` is at or before
`Instant.now()`, it changes the offer to `EXPIRED`, writes one SYSTEM `SlotOffer`/`EXPIRE`
audit with `reason=null`, then throws `OfferExpiredException`. The acceptance transaction
does not roll back for that exception.

**Reason.** The request should receive the expiry outcome while the database permanently
records that the stale offer is no longer actionable. Otherwise every acceptance attempt
could rediscover and roll back the same stale state.

**Where implemented.** `AcceptedOfferTerminalStateResolver.resolve(...)` delegates to
`SlotOfferAggressiveExpiryTransition.expireIfStale(...)`.
`OfferAcceptanceService.acceptOfferTransaction(...)` is annotated with
`noRollbackFor = {OfferExpiredException.class, ProviderIntervalOccupiedException.class}`.
`AcceptedOfferTerminalStateResolverTest` uses `NoRollbackExpiryHarness` and
`DefaultRollbackExpiryHarness` to prove that transaction configuration controls whether the
mutation commits.

**Risks/invariants protected.** An expired offer cannot remain `OFFERED` after a rejected
acceptance. The expiry comparison uses the application clock, so application/database clock
skew is not eliminated by this decision.

## 19. Use a separate W4 cleanup transaction after patient-overlap rollback

**Decision.** If the new appointment insert violates `no_patient_overlap`, the main W4
transaction rolls back. After rollback, the orchestrator opens a separate
`REQUIRES_NEW` transaction, locks RecoveryJob then SlotOffer, cancels the offer if it is
still `OFFERED`, writes a `SlotOffer`/`CANCEL` audit with reason
`PATIENT_SCHEDULE_CONFLICT`, preserves the RecoveryJob state, and rethrows
`PatientDoubleBookedException`.

**Reason.** Cancelling the offer inside the failed acceptance transaction would be rolled
back with the appointment insert. Starting cleanup before the failed transaction ends can
also self-block on locks. The outer orchestrator waits for rollback and then invokes the
separate proxied bean.

**Where implemented.** `OfferAcceptanceOrchestrator.acceptOffer(...)` catches only
`PatientDoubleBookedException` from `OfferAcceptanceService.acceptOfferTransaction(...)`.
It calls `OfferAcceptancePatientConflictCleanup.cleanupAfterPatientConflict(...)`, which is
annotated `READ_COMMITTED` and `Propagation.REQUIRES_NEW` and locks
`RecoveryJobRepository.findByIdForUpdate(...)` before
`SlotOfferRepository.findByIdForUpdate(...)`.

**Risks/invariants protected.** The failed appointment move and its broader acceptance
cascade remain atomic and roll back, while the unusable offer reaches a durable terminal
state. Cleanup returns without changes if the job or offer disappeared, and it leaves any
non-`OFFERED` offer untouched so it does not overwrite a concurrent terminal result.

## 20. Preserve every state change with an `AuditLog` row in the same transaction

**Decision.** Workflow services insert audit rows in the transaction that performs the
corresponding mutation. Actor fields use `USER` plus a non-null ID or `SYSTEM` plus a null
ID, as required by `audit_log_actor_valid`.

**Reason.** A committed state transition should have a durable explanation, and a rolled
back transition should not leave an audit claiming it happened. Exact action and reason
strings let tests and later operators distinguish causes.

**Where implemented.** Services save `AuditLog` through `AuditLogRepository`; W8 also
stores before/after JSON. `V2__core_workflow_tables.sql` defines the actor constraint.
`docs/WORKFLOW_IMPLEMENTATION_GUIDE.md` lists every workflow's exact `entityType`, action,
reason, actor, and conditional branches.

**Risks/invariants protected.** Audit rows follow transaction outcome. The current code
does not enforce a database foreign key from `(entity_type, entity_id)` to polymorphic
entities, so services and tests remain responsible for correct entity names and IDs.

## 21. Treat W13B as a targeted offer replacement in one transaction

**Decision.** `SchedulerReassignmentService.reassignSlot(...)` uses ordinary SlotOffer,
RecoveryJob, and source Appointment reads for routing, then locks the released Provider,
RecoveryJob, and targeted SlotOffer in that order. It validates the locked job as OPEN and
the locked offer through `AcceptedOfferTerminalStateResolver`, inserts the replacement
patient's Appointment, changes the offer from OFFERED to CANCELLED with reason
`STAFF_OVERRIDE`, and changes the job from OPEN to FILLED. The original WaitlistEntry is not
locked or mutated.

**Reason.** The caller names the offer that is being overridden. The RecoveryJob lock
serializes job-level changes, and `one_offered_per_recovery` guarantees there cannot be a
second OFFERED offer for that job. Therefore W12's plain post-job-lock offer-existence check
is unnecessary here. Keeping appointment insertion, offer cancellation, job fill, and their
USER audits in one transaction makes every ordinary failure roll back the complete
reassignment.

**Where implemented.** `SchedulerReassignmentService.reassignSlot(...)` runs under
`READ_COMMITTED` with `noRollbackFor=OfferExpiredException`. It calls
`ProviderRepository.findByIdForUpdate(...)`,
`RecoveryJobRepository.findByIdForUpdate(...)`, and
`SlotOfferRepository.findByIdForUpdate(...)` in that order. It reuses
`AppointmentBookingEligibilityValidator` and `BookingConstraintViolationTranslator`.
`SchedulerReassignmentServiceTest` covers 14 success, rejection, expiry, rollback, audit,
and unchanged-WaitlistEntry scenarios against PostgreSQL 15.

**Risks/invariants protected.** Provider-first locking stays consistent with booking and
recovery workflows. PostgreSQL exclusion constraints remain the authoritative final check
for provider and patient overlap. `PatientDoubleBookedException`,
`ProviderDoubleBookedException`, and unrecognized `DataIntegrityViolationException` use the
normal rollback rule, so the offer remains OFFERED and the job remains OPEN. A stale OFFERED
offer is the one exception: it changes to EXPIRED, receives a SYSTEM
`SlotOffer`/`EXPIRE`/null audit, and commits before `OfferExpiredException` is returned.

## 22. Cancel only PENDING provider blocks and lock only the block row

**Decision.** `ProviderBlockCancellationService.cancelPendingBlock(...)` locks the target
ProviderUnavailability directly and permits only PENDING to CANCELLED. It sets a
microsecond-truncated `cancelledAt`, preserves the original block `reason`, and writes one
`ProviderUnavailability`/`CANCEL` audit. ACTIVE and already CANCELLED blocks both throw
`ProviderBlockNotPendingException`; a missing row throws
`ProviderUnavailabilityNotFoundException`.

**Reason.** A PENDING block has not suppressed RecoveryJobs or cancelled SlotOffers. Its
cancellation is therefore local to one ProviderUnavailability row. Locking Provider would
add contention with bookings without protecting another state mutation. Cancelling an
ACTIVE block would require an undefined reversal of terminal SUPPRESSED and CANCELLED
states, so that transition is deliberately unsupported.

**Where implemented.** The service uses only
`ProviderUnavailabilityRepository.findByIdForUpdate(...)` and `AuditLogRepository`. It does
not import ProviderRepository, RecoveryJobRepository, or SlotOfferRepository. A null actor
produces SYSTEM/null audit attribution; a supplied actor produces USER/the supplied ID.
This actor rule is an analogy to existing workflows because the added cancellation operation
has no original locked actor specification.

**Risks/invariants protected.** Competing activation and cancellation operations serialize
on the ProviderUnavailability row. Cancellation never changes recovery or offer state and
cannot silently overwrite ACTIVE or CANCELLED. The current tests prove each sequential
state outcome; the simultaneous activation-versus-cancellation interleaving still requires
a true two-thread test.

## 23. Use the application clock for offer-expiry comparisons

**Decision.** Expiry-related time comparisons use Java `Instant.now()` in application code,
not PostgreSQL `clock_timestamp()` embedded in repository queries. This applies to
`SlotOfferAggressiveExpiryTransition`, `AcceptedOfferTerminalStateResolver`, and the
offer-expiry worker. `SlotOfferExpiryWorkerService.expireStaleOffers(...)` supplies its
`Instant.now()` value to the unlocked discovery query, then
`SlotOfferExpiryOfferProcessor.expireOfferIfEligible(...)` locks each discovered SlotOffer
through `SlotOfferRepository.findByIdForUpdate(...)`. The authoritative transition check
runs after that row lock and treats `expiresAt <= Instant.now()` as expired.

**Reason.** Using one application-clock rule keeps direct acceptance and background expiry
consistent. The unlocked worker query only discovers possible work; the post-lock check
decides whether the SlotOffer is still OFFERED and stale, so a changed or concurrently
resolved row is left untouched.

**Where implemented.** `AcceptedOfferTerminalStateResolver.resolve(...)` compares an
already-locked OFFERED SlotOffer with `Instant.now()` before calling
`SlotOfferAggressiveExpiryTransition.expireIfStaleOffered(...)`. The same transition is
called by `SlotOfferExpiryOfferProcessor.expireOfferIfEligible(...)` after it acquires the
SlotOffer row lock. `SlotOfferExpiryWorkerService.expireStaleOffers(...)` passes a Java
`Instant.now()` threshold to `SlotOfferRepository.findStaleOfferedIds(...)` for discovery.

**Risks/invariants protected.** Application and database clocks must be kept sufficiently
synchronized for operational accuracy. Correctness at the transition boundary does not
depend on the unlocked discovery snapshot because every discovered row is rechecked under
its SlotOffer lock before it is changed to EXPIRED and receives its SYSTEM
`SlotOffer`/`EXPIRE` audit.
