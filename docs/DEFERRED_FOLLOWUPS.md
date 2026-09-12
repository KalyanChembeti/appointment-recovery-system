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

**Current evidence.** `pom.xml` includes Spring MVC and Jakarta validation starters, but
`src/main/java/com/recoverysystem/web/` has no controllers or DTOs. No exception handler
maps the 32 domain exceptions to stable HTTP responses.

**Required follow-up.** Add request/response DTOs and controller endpoints for the accepted
workflow scope. Apply Bean Validation to required IDs, timestamps, date ranges, and request
fields before calling services; keep cross-row checks in transaction services. Add a
central exception-to-HTTP mapping that distinguishes the actual classes listed in
`WORKFLOW_IMPLEMENTATION_GUIDE.md`, including conflict, missing-row, expired-offer, and
validation outcomes. Add controller/security tests after endpoint contracts are fixed.

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

### React and TypeScript frontend

The README names React, TypeScript, and Vite as later work; no frontend directory or
JavaScript package manifest exists. Build it after REST and session contracts stabilize so
the UI consumes real endpoint/authentication behavior.

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
