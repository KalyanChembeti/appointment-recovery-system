# Deferred Follow-ups

This list contains work supported by the current source, configuration, README, or existing
design handoff. It does not treat an idea as implemented and does not add unrelated
infrastructure to the MVP.

## Phase 1 - close current workflow gaps

### Complete the W13A and W13B authorization boundaries

**Current evidence.** `OfferAcceptanceOrchestrator.acceptOffer(slotOfferId, patientId,
actorUserId)` separates the patient from the audit actor and can execute the W4 transaction
on behalf of another user. It does not authenticate `actorUserId`, load the actor's role, or
check RECEPTIONIST authorization. `OfferAcceptanceOnBehalfTest` proves the existing calling
convention and ownership check, but no controller or authenticated identity boundary exists.
`SchedulerReassignmentService.reassignSlot(existingSlotOfferId, replacementPatientId,
newAppointmentTypeId, actorUserId)` now implements the W13B transaction and attributes its
three success audits to USER/the supplied actor ID. It likewise does not authenticate that
ID or verify the RECEPTIONIST role.

**Required follow-up.** Expose both operations through the future REST/security boundary,
derive `actorUserId` from the authenticated server-side session, enforce the receptionist
role, and add tests proving the patient and actor cannot be forged through request data.

**Locked API-catalog gap.** No REST endpoint exists for
`SchedulerReassignmentService` (W13B). The service is fully built and tested, but the
locked API catalog does not specify an endpoint for it. Inventing one was deliberately
avoided rather than speculating on an unspecified capability.

### Implement ACTIVE block cancellation

**Current evidence.** `ProviderBlockCancellationService.cancelPendingBlock(...)` only
supports PENDING -> CANCELLED. Cancelling an ACTIVE ProviderUnavailability is explicitly
out of scope for that implementation: an ACTIVE block has already run
`RecoveryJobSuppressionCascade` (RecoveryJobs SUPPRESSED, SlotOffers CANCELLED), and no
design document anywhere in this project defines a mechanism for reversing that cascade.
SUPPRESSED is treated as a terminal RecoveryJob state everywhere else in this codebase.

**Required follow-up.** Decide whether ACTIVE block cancellation should ever un-suppress
affected RecoveryJobs/SlotOffers, or whether it should only flip the block's own status
without touching them (leaving suppressed capacity to be recovered independently, e.g. by
the source appointment being re-cancelled through the normal flow). Specify this decision
explicitly before implementing -- do not infer it from the PENDING case, which had no
cascade to consider in the first place.

### Resolve reconciliation behavior for already-resolved offers

**Current evidence.** Older design text describes an ACCEPTED offer found during
appointment reconciliation as an invariant violation.
`WaitlistReconciliationCascade.reconcileAnchoredWaitlistEntries(...)` currently queries only
ACTIVE WaitlistEntries and OFFERED SlotOffers, changes those rows, and never inspects an
ACCEPTED, DECLINED, EXPIRED, or CANCELLED offer. Tests explicitly prove DECLINED remains
untouched but do not define an ACCEPTED invariant failure.

**Required follow-up.** Decide whether current lenient selection is the accepted rule or
whether reconciliation must discover and reject an ACCEPTED offer. If changed, specify the
exception class and rollback behavior before modifying the shared W2/W3/W10/W11 cascade.

### Replace managed routing reads with projections

**Current evidence.** Genuine concurrent Testcontainers tests RM-01 and RM-02 showed that
Hibernate's session-level identity map can return stale field values when one transaction
first loads an entity through plain `findById(...)` for routing and later loads the same
entity type and ID through an `@Lock` query. The SQL lock is acquired, but Hibernate may
reuse the already-managed Java instance without refreshing it. The audited services now
copy their needed scalar values and call `EntityManager.detach(...)` before the later locked
read, as recorded in `docs/ARCHITECTURE_DECISIONS.md`.

**Required follow-up.** Consider replacing those plain routing reads with JPQL scalar or DTO
projections so they never add managed entities to the persistence context. Apply the change
consistently to every routing-read/locked-read pair, preserve the existing Provider ->
RecoveryJob -> WaitlistEntry -> SlotOffer lock hierarchy and validation order, and rerun the
genuine concurrent race matrix. Until that refactor is complete, every new managed routing
read followed by a lock of the same entity type and ID must detach the routing entity after
extracting its scalar values.

## Phase 1 - Step 7 concurrent race matrix

The same-Provider direct-booking race and the two-worker W12 race currently run two workflow
transactions at the same time. Add true concurrent PostgreSQL Testcontainers coverage for:

1. accept A versus accept A;
2. accept A versus decline A;
3. accept A versus expiry A;
4. accept A versus accept B for the same patient/old Appointment;
5. cancellation versus reschedule of one Appointment;
6. two bookings for one patient/interval on different Providers;
7. provider-block request versus booking on the same Provider/interval;
8. block activation versus recovery generation;
9. block activation versus direct booking;
10. recovery generation versus removal of the selected WaitlistEntry;
11. acceptance versus sibling WaitlistEntry removal;
12. mutation of a selected W12 candidate between ranking and row lock;
13. permanently ineligible candidate exclusion while related rows change;
14. W4 acceptance losing the patient exclusion-constraint race, followed by
    `OfferAcceptancePatientConflictCleanup`;
15. complete final-row and audit assertions for the existing same-Provider booking race.

**RM-04 outcome coverage note.** Outcomes (a)/(b), where
`OfferAcceptancePatientConflictCleanup` runs after the reactive patient
exclusion-constraint path under genuine two-sided concurrent contention, were not observed
across eight real concurrent runs or the subsequent manual restoration check. The cleanup
mechanism itself is proven by its existing sequential test and remains represented as a
reachable legal outcome in `RaceMatrixOfferAcceptanceTest`, which accepts both exception
types and both cancellation reasons. Diagnostic timing explained the natural distribution:
Category D proactive discovery ran before either side's Appointment INSERT and structurally
dominated any one-sided timing variation. Delaying one side would only change which side's
Category D cleanup wins; it would not expose the reactive constraint path. Forcing outcomes
(a)/(b) would require a symmetric two-sided barrier across both transactions'
discovery-to-lock windows, a qualitatively different technique from the one-sided delays and
spies used elsewhere in this phase. That technique was not attempted because the cleanup
mechanism is already established independently.

RM-09 and RM-10 use the corrected Correction 6 rule that PENDING blocks stop both direct
bookings and W12 offer creation. RM-09 begins with a PENDING block, and RM-10 races booking
against block creation because booking against a pre-existing PENDING block is not a
timing-dependent activation race. `RaceMatrixProviderBlockTest` covers both corrected cases.

The two-worker W12 case is complete. Both workers may discover the same OPEN job before
either locks it. After the first creates an OFFERED offer and leaves the job OPEN, the
second waits for the RecoveryJob lock, performs the non-locking
`existsByRecoveryJobIdAndStatus(jobId, OFFERED)` read, and returns
`OFFER_ALREADY_EXISTS_FOR_JOB` without changing rows or writing an audit. The concurrent
test proves the result multiset is `{OFFER_CREATED, OFFER_ALREADY_EXISTS_FOR_JOB}` and that
exactly one SlotOffer row is committed.

Each race test must use two real threads/transactions, latches or barriers around the
intended serialization point, bounded timeouts, exact winner/loser assertions, and committed
final-state/audit queries. Sequential calls through separate transactional beans do not
meet this requirement.

## Phase 1 - REST API and validation

### Add provider working-hours management

**Current evidence.** No transactional service or REST endpoint creates or updates
`ProviderSchedule` rows. Working hours are currently constructed directly only by test fixtures,
and working-hours management was not one of the 14 implemented workflows.

**Required follow-up.** Define and implement an authenticated provider/admin workflow for setting
and updating provider working hours before exposing a REST endpoint for that capability.

**Current evidence.** `pom.xml` includes Spring MVC and Jakarta validation starters, but
`src/main/java/com/recoverysystem/web/` has no controllers or DTOs. No exception handler
maps the 32 domain exceptions to stable HTTP responses.

**Required follow-up.** Add request/response DTOs and controller endpoints for the accepted
workflow scope. Apply Bean Validation to required IDs, timestamps, date ranges, and request
fields before calling services; keep cross-row checks in transaction services. Add a
central exception-to-HTTP mapping that distinguishes the actual classes listed in
`WORKFLOW_IMPLEMENTATION_GUIDE.md`, including conflict, missing-row, expired-offer, and
validation outcomes. Add controller/security tests after endpoint contracts are fixed.

**Error-response compatibility note.** `BadCredentialsException` and
`MethodArgumentNotValidException` are the only two error responses in the API that do not
carry a `code` field in `GlobalExceptionHandler`. Their exact pre-existing response shapes
from `AuthController`, before consolidation, were preserved verbatim rather than reshaped
to match `ApiErrorResponse`'s four-field contract, avoiding an unnecessary regression in
already-passing tests. A future frontend consuming this API must special-case these two
response shapes rather than assume every error carries a `code` field.

Actor IDs and patient IDs that come from authenticated identity must not be accepted as
freely forgeable request fields. W5, W7, W11, and W13A need particular care because their
service signatures assume a required human actor but do not all perform explicit null or
role checks.

## Phase 1 - Spring Security and server-side sessions

**Current evidence.** `pom.xml` contains `spring-boot-starter-security` and
`spring-security-test`. `application.yml` configures an HttpOnly, SameSite=Lax session
cookie, a 30-minute timeout, and `spring.session.store-type: jdbc`. There is no Spring
Session JDBC dependency, session-table Flyway migration, custom `SecurityFilterChain`,
login/logout flow, user-details adapter, password verification service, or role rule. The
fresh build logs Spring Boot's generated development password.

**Required follow-up.** Add the Spring Session JDBC implementation required by the chosen
store, manage its database objects through Flyway so Hibernate never generates DDL, and
configure login/logout, CSRF, cookies, and role authorization. Resolve the authenticated
`User.id` and `UserRole` at the web boundary and pass trusted actor/patient IDs to services.
Add security tests for PATIENT, RECEPTIONIST, PROVIDER, ADMIN, unauthenticated, and
cross-patient requests according to each endpoint's approved contract.

JWT, Redis, and a custom external identity service are not current MVP requirements.

## Phase 1 - scheduled workers

**Current evidence.** `AppointmentRecoverySystemApplication` has `@EnableScheduling`, and
`application.yml` defines
`recovery-system.worker.recovery-poll-interval-ms: 60000` and
`offer-expiry-poll-interval-ms: 30000`. No method has `@Scheduled`.
`RecoveryWorkerService.attemptRecovery()` and
`SlotOfferExpiryWorkerService.expireStaleOffers(int)` can only run when called directly.

**Required follow-up.** Add thin scheduled adapter methods that use the two configured poll
intervals and call the existing transaction services. Define the expiry batch size and
failure logging/continuation behavior. Test that one failed job/offer invocation does not
silently stop later scheduler runs. The handled same-job two-worker race is covered, but the
remaining race matrix must pass before claiming all multiple-instance polling interleavings
are safe.

## Phase 1 - documentation and repository hygiene

### Update `README.md`

The README still says schema/entities are unimplemented and presents populated `config`,
`security`, `web`, and `worker` packages. It also describes Phase 0 race-matrix validation
in a way that can be read as complete concurrency testing, while only two true race tests
exist. Update the status, package tree, build count, and local behavior after the current
handoff is reviewed.

### Repair missing documentation references

Source Javadoc and `docs/DOCUMENT_INVENTORY.md` name files such as
`IMPLEMENTATION_HANDBOOK.md`, `SECTION_2C_V3_COMPLETE.md`, and output documents that are not
present in this repository. Point those references to maintained repository files or remove
them after deciding which documents are canonical.

### Remove tracked Maven output

Fifteen files under `target/` are tracked. A clean Maven build therefore changes generated
files in Git until they are restored. Remove tracked `target/` content in a separate
repository-hygiene change and verify `.gitignore` keeps future build output untracked.

## Phase 2 - user interface and delivery

### Add real-browser authentication integration coverage

**Current evidence.** Stage 1 unit tests cover fetch configuration, CSRF headers, error
normalization, and login UI state. A Node test runner cannot faithfully prove browser cookie
storage, HttpOnly enforcement, SameSite behavior, or Vite proxy forwarding together.

**Required follow-up.** Add a Playwright browser test that runs the UI and backend and
proves login, CSRF bootstrap, `SESSION` persistence, and authenticated `/api` proxy requests
as one flow. Keep this separate from Vitest's unit-level mocks.

### Expand the React and TypeScript frontend

Stage 1 now provides a separate React 18, TypeScript, and Vite build with a typed API
client, authentication context, and login screen. Add later workflow pages only after their
UI contracts are defined; routing and broader client state management remain unnecessary
while login is the sole screen.

### Add receptionist-on-behalf booking after patient search exists

**Stage 2B scope boundary.** The booking screen deliberately supports PATIENT self-booking
only. Receptionist-on-behalf booking remains deferred because the backend has no authenticated
patient list or search capability from which staff can select the intended patient. Define and
secure that lookup contract before adding the receptionist flow; do not accept a freely entered
patient ID as a substitute.

### Local application containers

`docker-compose.yml` currently starts PostgreSQL 15 only. Add the Spring Boot application
and frontend to the local deployment definition after their runtime configuration and
health endpoints exist.

### CI/CD

No GitHub Actions workflow or other CI pipeline is present. Add a pipeline that compiles
with Java 21 and can run Docker-backed PostgreSQL Testcontainers tests, then package the
application/frontend artifacts. Add deployment stages only after the AWS target is defined.

### AWS deployment

AWS deployment is listed in the project plan but no infrastructure or deployment code is
present. Choose the actual compute, PostgreSQL, session, secrets, TLS, and networking setup
before adding infrastructure files; the repository currently provides no basis for claiming
an AWS deployment.

### Observability and load testing

The current application has basic logging levels only. There are no metrics dashboards,
traces, alert rules, structured operational runbooks, or load-test scripts. Add them after
the API and scheduled workloads exist. Record measured throughput, latency, database lock
waits, worker lag, offer expiry delay, and error rates before making performance or scale
claims.
