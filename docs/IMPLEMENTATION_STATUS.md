# Implementation Status

## Snapshot used for this handoff

This document was created from commit `552505e86d0ee9ccc22d7c3017f2c6acbbd24ab0` on
2026-09-11 and refreshed after the W12 same-job worker race fix and the dedicated W13A
calling-convention tests. The current source changes add the post-RecoveryJob-lock offered
slot check and its outcome; the current test changes cover that race and W13A accept on
behalf. The six handoff files under `docs/` are still uncommitted documentation artifacts.

A fresh `mvn clean install` completed with `BUILD SUCCESS` on 2026-09-11. Surefire reported
244 tests run, 0 failures, 0 errors, and 0 skipped. The build took 2 minutes 1 second. The
tracked and untracked `target/` output produced by Maven was removed or restored after the
result was recorded so generated output is not part of the documentation change.

## Project purpose

The Dynamic Appointment Recovery System manages provider schedules, direct booking,
appointment lifecycle changes, patient waitlists, and time-limited offers that refill
released appointment capacity. PostgreSQL holds the transactional source of truth.
Recovery work is represented by durable `RecoveryJob` rows rather than an in-memory queue.
The current repository contains the schema, JPA persistence layer, transaction services,
and integration tests. It does not yet contain REST endpoints, application security rules,
or scheduled invocations of the worker services.

## Current stack

| Component | Current version or setting | Source |
|---|---|---|
| Java | 21; build runtime was Microsoft OpenJDK 21.0.12.1 | `pom.xml`, fresh `mvn --version` |
| Maven | 3.9.9 in the verification environment | fresh `mvn --version` |
| Spring Boot | 3.3.4 | `pom.xml` |
| Hibernate ORM | 6.5.3.Final, supplied by Spring Boot | fresh build output |
| PostgreSQL JDBC | 42.7.4, managed by Spring Boot | `pom.xml`, resolved Maven dependency tree |
| PostgreSQL | `postgres:15`; the verification container resolved to PostgreSQL 15.19 | `docker-compose.yml`, tests, fresh build output |
| Flyway | `flyway-core` 10.10.0 plus `flyway-database-postgresql` 10.10.0, managed by Spring Boot | `pom.xml`, resolved Maven dependency tree |
| Testcontainers | 1.20.1 | `pom.xml` |
| Database schema mode | Flyway enabled; Hibernate `ddl-auto: validate` | `src/main/resources/application.yml` |
| Time handling | Hibernate JDBC timezone UTC; clinic zone `America/New_York` | `src/main/resources/application.yml` |
| Web/security dependencies | Spring MVC, validation, and Spring Security starters are present; no Spring Session JDBC dependency is present | `pom.xml` |

There are no Kafka, Redis, Kubernetes, or microservice dependencies in `pom.xml`.

## Repository and package structure

The production application root is `src/main/java/com/recoverysystem/`:

- `AppointmentRecoverySystemApplication.java` starts Spring Boot and has
  `@EnableScheduling`.
- `domain/entity/` contains 12 JPA entities: `User`, `Specialty`, `AppointmentType`,
  `Provider`, `ProviderSchedule`, `Appointment`, `ProviderUnavailability`, `RecoveryJob`,
  `WaitlistEntry`, `SlotOffer`, `SchedulingPolicy`, and `AuditLog`.
- `domain/enums/` contains 10 Java enums, including `AppointmentStatus`,
  `ProviderUnavailabilityStatus`, `RecoveryJobStatus`, `SlotOfferStatus`,
  `WaitlistEntryStatus`, and `RecoveryWorkerOutcome`.
- `repository/` contains 12 Spring Data JPA repositories. Locking queries live in
  `AppointmentRepository`, `ProviderRepository`, `ProviderUnavailabilityRepository`,
  `RecoveryJobRepository`, `WaitlistEntryRepository`, and `SlotOfferRepository`.
- `service/` contains 28 workflow services and shared helpers. The package includes booking,
  appointment lifecycle, waitlist, offer, provider-block, and recovery-worker behavior.
- `exception/` contains 32 domain exception classes.
- `config/`, `security/`, `web/`, and `worker/` currently contain no Java source files.

Resources live in `src/main/resources/`. Tests live in
`src/test/java/com/recoverysystem/`; there are 23 `*Test.java` classes and two transactional
test harnesses under `support/`. The only application container in `docker-compose.yml` is
PostgreSQL; the Spring Boot application and a frontend are not defined there.

## Database and Flyway status

Flyway is the schema authority. `src/main/resources/application.yml` keeps
`spring.jpa.hibernate.ddl-auto: validate` and configures
`spring.flyway.locations: classpath:db/migration`.

Two migrations exist:

1. `src/main/resources/db/migration/V1__reference_tables.sql` creates `users`, `specialty`,
   `appointment_type`, `provider`, and `provider_schedule`, plus indexes on
   `provider.specialty_id` and `provider_schedule.provider_id`.
2. `src/main/resources/db/migration/V2__core_workflow_tables.sql` installs `btree_gist` and
   creates `appointment`, `provider_unavailability`, `recovery_job`, `waitlist_entry`,
   `slot_offer`, `scheduling_policy`, and `audit_log` with their constraints and indexes.

The result is 12 application tables. IDs use PostgreSQL `BIGINT GENERATED ALWAYS AS
IDENTITY`; timestamps use `TIMESTAMPTZ`; enum-like values use `VARCHAR` plus `CHECK`
constraints. The migration also creates one default `scheduling_policy` row with a
30-minute minimum recovery lead and a 10-minute offer duration.

The most important database invariants are:

- `no_patient_overlap` and `no_provider_overlap` are partial GiST exclusion constraints on
  half-open `[start_at, end_at)` ranges for `SCHEDULED` appointments.
- `uq_appointment_replaced_by_appointment_id` permits an appointment to replace at most one
  old appointment.
- `recovery_job.source_appointment_id` is unique.
- `one_offered_per_recovery` permits at most one `OFFERED` `SlotOffer` per `RecoveryJob`.
- `appointment_cancellation_reason_required`,
  `appointment_reschedule_replacement_required`, and
  `appointment_cannot_replace_itself` protect appointment lifecycle state.
- `waitlist_entry_date_range_valid` protects the inclusive date window.
- `audit_log_actor_valid` requires a `USER` audit to have `actor_user_id` and a `SYSTEM`
  audit to have no `actor_user_id`.

`SchemaMigrationTest` runs Flyway programmatically against PostgreSQL 15 and verifies the
tables, columns, extension, constraints, indexes' effects, and default policy row.

## Entity and repository status

All 12 Flyway-managed tables have matching JPA entities and Spring Data repositories.
Entities store foreign keys as scalar `Long` fields; they do not declare JPA relationships.
Enum fields use `@Enumerated(EnumType.STRING)`. `AuditLog.oldValues` and `newValues` are
mapped to PostgreSQL `jsonb` as strings with Hibernate JSON JDBC type metadata. Entity
lifecycle callbacks assign `Instant` timestamps truncated to microseconds where the service
does not assign an explicit transition time.

Repository locking is explicit and pessimistic. Every `@Lock` method uses
`LockModeType.PESSIMISTIC_WRITE`. Multi-row lock queries include `ORDER BY ...id ASC` so
same-type locks are acquired in ascending ID order. The complete lock method inventory and
per-workflow order are in `docs/CONCURRENCY_AND_LOCKING.md`.

## Workflow checkpoint

The workflow numbering comes from `docs/PROJECT_1_MASTER_HANDOFF.md`. The status below uses
the current source and tests, including partial or missing workflow implementations.

| Workflow | Name | Service and main method | Current status | Shared components | Test class |
|---|---|---|---|---|---|
| W1 | Direct booking | `DirectBookingService.bookAppointment(...)` | Implemented and tested | `AppointmentBookingEligibilityValidator`, `BookingConstraintViolationTranslator` | `DirectBookingServiceTest` |
| W2 | Normal appointment cancellation | `AppointmentCancellationService.cancelAppointment(...)` | Implemented and tested | `WaitlistReconciliationCascade` | `AppointmentCancellationServiceTest` |
| W3 | Direct reschedule | `AppointmentReschedulingService.rescheduleAppointment(...)` | Implemented and tested | `AppointmentBookingEligibilityValidator`, `BookingConstraintViolationTranslator`, `WaitlistReconciliationCascade` | `AppointmentReschedulingServiceTest` |
| W4 | Offer acceptance | `OfferAcceptanceOrchestrator.acceptOffer(...)` and `OfferAcceptanceService.acceptOfferTransaction(...)` | Implemented and tested with sequential transactional integration tests | `AcceptedOfferTerminalStateResolver`, `SlotOfferAggressiveExpiryTransition`, `SlotOfferCleanupDiscovery`, `OfferAcceptancePatientConflictCleanup`, `BookingConstraintViolationTranslator` | `OfferAcceptanceOrchestratorTest`, `AcceptedOfferTerminalStateResolverTest`, `SlotOfferCleanupDiscoveryTest`, `OfferAcceptancePatientConflictCleanupTest` |
| W5 | Offer decline | `SlotOfferDeclineService.declineOffer(...)` | Implemented and tested | Direct `SlotOffer` row lock | `SlotOfferDeclineServiceTest` |
| W6 | Offer expiry | `SlotOfferExpiryWorkerService.expireStaleOffers(...)` and `SlotOfferExpiryOfferProcessor.expireOfferIfEligible(...)` | Implemented and tested; no scheduler invokes it yet | `SlotOfferAggressiveExpiryTransition` | `SlotOfferExpiryWorkerServiceTest`, `SlotOfferExpiryOfferProcessorTest` |
| W7 | Waitlist entry creation | `WaitlistEntryCreationService.createWaitlistEntry(...)` | Implemented and tested | Anchor appointment lock and specialty validation | `WaitlistEntryCreationServiceTest` |
| W8 | Waitlist entry modification | `WaitlistEntryModificationService.modifyWaitlistEntry(...)` | Implemented and tested | Anchor appointment lock followed by entry lock | `WaitlistEntryModificationServiceTest` |
| W9 | Waitlist entry removal | `WaitlistEntryRemovalService.removeWaitlistEntry(...)` | Implemented and tested | Offered-slot cancellation under locks | `WaitlistEntryRemovalServiceTest` |
| W10 | Appointment completion | `AppointmentCompletionService.completeAppointment(...)` | Implemented and tested | `WaitlistReconciliationCascade` | `AppointmentCompletionServiceTest` |
| W11 | Appointment no-show | `AppointmentNoShowService.markNoShow(...)` | Implemented and tested | `WaitlistReconciliationCascade` | `AppointmentNoShowServiceTest` |
| W12 | Recovery worker offer generation | `RecoveryWorkerService.attemptRecovery()` | Implemented and tested with sequential integration tests and a true same-job two-worker test; no scheduler invokes it yet | `RecoveryJobEligibilityClassifier`, `RecoveryCandidateSelector`, `RecoveryCandidateRevalidator` | `RecoveryWorkerServiceTest`, `RecoveryJobEligibilityClassifierTest`, `RecoveryCandidateSelectorTest`, `RecoveryCandidateRevalidatorTest` |
| W13A | Scheduler accepts on behalf | Reuses `OfferAcceptanceOrchestrator.acceptOffer(slotOfferId, patientId, actorUserId)` | Transaction mechanics and the separate patient/receptionist calling convention are tested; no REST endpoint or authenticated receptionist authorization exists | Entire W4 acceptance pipeline | `OfferAcceptanceOnBehalfTest` |
| W13B | Scheduler reassigns to another patient | No service method exists | Not implemented and not tested | None | None |
| W14 | Provider-block request and activation | `ProviderBlockCreationService.createProviderBlock(...)` and `ProviderBlockActivationService.activateProviderBlock(...)` | Creation and `PENDING -> ACTIVE` are implemented and tested; `PENDING -> CANCELLED` is absent | `RecoveryJobSuppressionCascade` | `ProviderBlockCreationServiceTest`, `ProviderBlockActivationServiceTest` |

Detailed state transitions, audits, exception classes, and lock sequences for every row are
in `docs/WORKFLOW_IMPLEMENTATION_GUIDE.md`.

## Test and build status

The fresh build result is:

```text
BUILD SUCCESS
Tests run: 244, Failures: 0, Errors: 0, Skipped: 0
```

All integration tests use PostgreSQL 15 through Testcontainers. Two tests use real
two-thread workflow transactions:
`DirectBookingServiceTest.concurrentOverlappingProviderBookingsProduceOneWinner` and
`RecoveryWorkerServiceTest.concurrentWorkersCreateOneOfferAndReturnHandledRaceOutcome`.
Both use a fixed two-thread `ExecutorService` and two `CountDownLatch` instances. Other
transaction-boundary tests, including the W4 rollback/`REQUIRES_NEW` cleanup tests, run
transactions sequentially and must not be treated as proof of concurrent interleavings.
See `docs/TESTING_STATUS.md`.

The build emitted no errors. It emitted these warnings or expected diagnostic messages:

- Java compilation and test compilation warned that annotation processing may be disabled
  by default in a future `javac` release.
- Spring Security printed a generated development password because no application
  `SecurityFilterChain` or production identity configuration exists yet.
- Negative database tests caused Hibernate/PostgreSQL constraint-violation log messages;
  those tests passed because the violations were expected.

## Remaining Phase 1 work

1. Resolve or explicitly defer the current workflow gaps: W13B scheduler reassignment has
   no implementation, W13A has no API/role enforcement, and W14 has no
   `PENDING -> CANCELLED` transition.
2. Add the Step 7 true-concurrency race matrix described in
   `docs/CONCURRENCY_AND_LOCKING.md`. Sequential integration tests already verify state and
   rollback behavior but do not exercise most two-transaction interleavings.
3. Add REST controllers, request/response DTOs, Jakarta Bean Validation, stable HTTP error
   mapping, and transaction-service entry points.
4. Configure Spring Security with server-side session identity and role authorization.
   The dependencies and JDBC session settings exist, but the application currently uses
   Spring Boot's generated development user.
5. Add scheduled adapters for `RecoveryWorkerService.attemptRecovery()` and
   `SlotOfferExpiryWorkerService.expireStaleOffers(int)`. `@EnableScheduling` and polling
   properties exist, but no method has `@Scheduled`.

## Remaining Phase 2 work

- Build the React and TypeScript frontend described by the project plan.
- Add the Spring Boot application and frontend to local container orchestration; current
  `docker-compose.yml` starts PostgreSQL only.
- Add CI/CD, including a GitHub Actions build that can run the PostgreSQL Testcontainers
  suite.
- Add the planned AWS deployment.
- Add production observability and measured load/performance testing. No throughput,
  latency, or scale result should be claimed before those measurements exist.
- Bring `README.md` up to date after the API and delivery shape are settled; its current
  implementation-status section predates the migrations, entities, services, and tests.

## Known implementation / spec differences

| Area | Older or stated expectation | Current implemented behavior | Future action required? |
|---|---|---|---|
| Overall workflow count | The handoff request says all 14 workflows are complete. | W1-W12 and W14 request/activation exist. W13A reuses tested W4 mechanics but lacks its API/authorization boundary. W13B has no implementation. | Yes. Implement W13B and W13A's authenticated boundary or revise the accepted scope. |
| W12 outcomes | The handoff request refers to seven terminal/proceed outcomes. | `RecoveryWorkerOutcome` contains nine values: `NO_OPEN_JOBS`, `OFFER_CREATED`, `OFFER_ALREADY_EXISTS_FOR_JOB`, `RELEASED_INTERVAL_OCCUPIED`, `PROVIDER_ACTIVELY_BLOCKED`, `PROVIDER_PENDING_BLOCKED`, `LEAD_TIME_CLOSED`, `NO_ELIGIBLE_CANDIDATE`, and `CANDIDATE_BECAME_STALE`. | Documentation must preserve all nine. The ninth outcome deliberately handles a stale routing-read race after the RecoveryJob lock. |
| Provider-block lifecycle | The older lifecycle requires both `PENDING -> ACTIVE` and `PENDING -> CANCELLED`. | `ProviderBlockActivationService` implements `PENDING -> ACTIVE`; no cancellation service or method implements `PENDING -> CANCELLED`. | Yes, unless cancellation is removed from scope. |
| Reconciliation invariant handling | The older design text says an `ACCEPTED` offer encountered during reconciliation is an invariant violation. | `WaitlistReconciliationCascade` selects only `ACTIVE` entries and only `OFFERED` offers. It removes/cancels those rows and does not inspect `ACCEPTED`, `DECLINED`, `EXPIRED`, or already `CANCELLED` offers. | Needs a product/design decision before changing current tested behavior. |
| Offer expiry clock | Older design material discussed database-clock expiry checks. | `SlotOfferExpiryWorkerService`, `SlotOfferExpiryOfferProcessor`, and `AcceptedOfferTerminalStateResolver` compare against Java `Instant.now()`; the worker discovery uses `< :now`, while the locked transition treats `expiresAt <= now` as stale. | Needs verification if database-clock semantics are required. |
| Scheduling policy cardinality | The migration inserts one default policy row. | The database does not enforce a singleton; `RecoveryJobEligibilityClassifier` and `RecoveryWorkerService` call `findAll().getFirst()`, which fails if no row exists and silently chooses one if multiple rows exist. | Yes if administrators will edit policy data. Define and enforce selection/cardinality rules. |
| Server-side session security | Server-side sessions and no JWT are the planned architecture. | `application.yml` contains JDBC-session properties, but `pom.xml` has no Spring Session JDBC dependency. There are no controllers, login flow, `SecurityFilterChain`, role checks, or code that derives workflow actors from an authenticated session. | Yes, Phase 1 REST/security work must add the session dependency and identity boundary. |
| Scheduled workers | Polling properties and `@EnableScheduling` suggest automatic workers. | No `@Scheduled` method invokes the recovery or expiry worker service. | Yes, Phase 1 scheduling work. |
| Repository overview | `README.md` describes the database and domain implementation as pending and lists populated `config`, `security`, `web`, and `worker` packages. | The database, entities, repositories, services, and 244 tests now exist; those four packages have no Java source files. | Yes. Update the README when the current handoff is accepted. |
| Documentation references | Source Javadoc and `DOCUMENT_INVENTORY.md` refer to design/output documents such as `IMPLEMENTATION_HANDBOOK.md` and `SECTION_2C_V3_COMPLETE.md`. | Those named files are not present in this repository; `docs/PROJECT_1_MASTER_HANDOFF.md` and the current handoff files are present. | Yes. Replace or remove dead references when documentation ownership is settled. |
| Build output in Git | Build output should normally be disposable. | Fifteen files under `target/` are tracked, so `mvn clean install` modifies tracked generated artifacts until they are restored. | Yes. Remove tracked build output and ensure `target/` remains ignored in a separate repository-hygiene change. |

## Current stopping point

The transaction services and PostgreSQL integration suite are stable at 244 passing tests,
but the source does not support the claim that every W1-W14 entry is complete. The next
implementation task is to resolve the missing W13B and W14 `PENDING -> CANCELLED` scope,
then write the Step 7 true-concurrency tests for the implemented lock graph. After those
races pass, add REST endpoints and session-based Spring Security, followed by scheduled
adapters for W6 and W12.
