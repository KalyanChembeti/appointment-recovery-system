# Project 1 Master Handoff — Dynamic Appointment Recovery System

**Project:** Resume and Applications  
**Purpose:** Canonical handoff for continuing Project 1 in a new chat without reopening design work.

---

## 1. Why This Project Exists

Project 1 is a portfolio backend system designed to close practical SDE/backend gaps through real implementation rather than resume keyword stuffing.

Primary skill goals:

- Java 21
- Spring Boot
- REST API design
- PostgreSQL
- transactions and concurrency
- database constraints and indexing
- authentication and RBAC
- testing with JUnit / Mockito / Testcontainers
- Docker
- GitHub Actions
- AWS deployment
- React / TypeScript frontend
- observability and performance testing

The project should demonstrate end-to-end ownership:

```text
requirements
→ domain modeling
→ transaction design
→ implementation
→ testing
→ deployment
→ monitoring
→ troubleshooting
```

Do not introduce Kafka, Redis, Kubernetes, microservices, or other infrastructure simply for keywords. Project 1 remains a modular monolith unless implementation exposes a genuine need.

---

# 2. System

## Dynamic Appointment Recovery System

Core problem:

Cancelled outpatient appointments create unused capacity while other patients wait for earlier appointments.

When recoverable capacity becomes available, the system:

```text
released appointment capacity
→ durable RecoveryJob
→ rank eligible waitlisted patients
→ create one time-limited SlotOffer
→ patient accepts / declines / offer expires
→ safely reassign capacity
```

The system must prevent:

- provider double-booking
- patient double-booking
- duplicate acceptance
- stale offer acceptance
- inconsistent reschedule state
- deadlocks from inconsistent lock ordering
- recovery work being lost after crashes

---

# 3. Final Technology Stack

## Backend

```text
Java 21
Spring Boot 3.x
Maven
Spring Web
Spring Data JPA / Hibernate
Spring Security
PostgreSQL 15+
Flyway
JUnit 5
Mockito
Testcontainers
```

## Frontend

```text
React
TypeScript
Vite
```

TypeScript is the primary frontend language, so the project also provides practical JavaScript ecosystem experience.

## Infrastructure

```text
Docker
Docker Compose
GitHub Actions
AWS
```

No Kubernetes for MVP.

No Kafka or RabbitMQ for MVP.

No Redis unless implementation later demonstrates an actual requirement.

---

# 4. Architecture

Use a **modular monolith**.

Single clinic for MVP.

Store timestamps in UTC.

Use a configurable IANA clinic timezone for display and business-time rules, e.g.:

```text
America/New_York
```

Do not hardcode timezone logic into the domain.

---

# 5. Authentication & Authorization

Use **server-side session authentication**, not JWT.

```text
Spring Security
HttpOnly cookies
Secure cookies in production
SameSite protection
30-minute inactivity timeout
RBAC
```

Roles:

```text
PATIENT
RECEPTIONIST
PROVIDER
ADMIN
```

Public registration can create only:

```text
PATIENT
```

Patient-facing APIs derive patient identity from the authenticated session.

Patients must not submit arbitrary `patient_id` values.

Receptionists/admins may act on behalf of patients where explicitly permitted.

No Google OAuth / external SSO in MVP.

---

# 6. Domain Model

Core entities:

1. User
2. Provider
3. Specialty
4. AppointmentType
5. Appointment
6. RecoveryJob
7. WaitlistEntry
8. SlotOffer
9. ProviderSchedule
10. ProviderUnavailability
11. SchedulingPolicy
12. AuditLog

### Important entity meanings

**Appointment**
- actual booking / capacity consumption / history

**RecoveryJob**
- durable identity of one released-capacity recovery opportunity

**WaitlistEntry**
- patient demand for an earlier appointment

**SlotOffer**
- time-limited response opportunity for a particular RecoveryJob

**ProviderUnavailability**
- provider capacity reservation / finalized unavailability

---

# 7. Core State Machines

## Appointment

```text
SCHEDULED
→ COMPLETED
→ CANCELLED
→ NO_SHOW
```

`RESCHEDULED` is represented as:

```text
Appointment.status = CANCELLED
cancellation_reason = RESCHEDULED
replaced_by_appointment_id = new appointment
```

## WaitlistEntry

```text
ACTIVE
→ FULFILLED
→ REMOVED
```

## SlotOffer

```text
OFFERED
→ ACCEPTED
→ DECLINED
→ EXPIRED
→ CANCELLED
```

## RecoveryJob

```text
OPEN
→ FILLED
→ EXHAUSTED
→ SUPPRESSED
```

Terminal meanings:

```text
FILLED
= recovered interval is now occupied

EXHAUSTED
= no eligible untried candidate exists for this recovery opportunity

SUPPRESSED
= automatic recovery is permanently stopped for this job
  because capacity became unusable / policy closed
```

---

# 8. Key Database Constraints

PostgreSQL is the final defense against booking overlap.

Conceptually:

```sql
EXCLUDE USING gist (
    patient_id WITH =,
    tstzrange(start_at, end_at, '[)') WITH &&
)
WHERE (status = 'SCHEDULED');
```

And equivalent provider exclusion constraint.

Other important constraints:

```text
RecoveryJob.source_appointment_id UNIQUE

one OFFERED SlotOffer per RecoveryJob
  via partial unique index

replaced_by_appointment_id unique when non-null
```

Do not describe overlap protection as a normal UNIQUE constraint.

---

# 9. Canonical Lock Hierarchy

The modeled explicit lock graph is:

```text
Provider(s)
→ ProviderUnavailability [conditional when mutating existing row]
→ Appointment(s)
→ RecoveryJob(s)
→ WaitlistEntry(s)
→ SlotOffer(s)
```

Within the same entity type:

```text
collect IDs
deduplicate
sort ascending by primary key
lock in that order
```

Isolation:

```text
READ COMMITTED
+ explicit row locks
+ post-lock revalidation
+ PostgreSQL constraints
```

Do not claim this is globally equivalent to SERIALIZABLE.

The design goal is to preserve the system's specified invariants under READ COMMITTED.

---

# 10. Critical Synchronization Rules

## Post-parent-lock discovery

Do not derive dependent lock sets from stale routing reads.

Examples:

```text
lock Appointment
→ then query anchored WaitlistEntries

lock WaitlistEntries
→ then query their actionable SlotOffers
```

## Worker rule

Recovery worker handles **one candidate attempt per transaction**.

Do not loop through multiple candidates while holding one candidate row lock.

If selected candidate became stale because of a race:

```text
commit/no-op
RecoveryJob remains OPEN
retry in later worker transaction
```

---

# 11. RecoveryJob Creation Rule

Durable recovery work is created atomically with capacity release.

```text
capacity release
+ conditional RecoveryJob creation
= same transaction
```

Matching and offer generation happen post-commit in worker transactions.

### Old/released interval availability

**ACTIVE ProviderUnavailability:**

```text
operation releasing old appointment still succeeds
but no RecoveryJob is created
```

**PENDING ProviderUnavailability:**

```text
operation releasing old appointment still succeeds
RecoveryJob MAY be created
worker will not process it while block remains PENDING
```

This distinction is important.

---

# 12. ProviderUnavailability Model

Use explicit status:

```text
PENDING
ACTIVE
CANCELLED
```

Semantics:

## PENDING

```text
temporary reservation while conflicting appointments are being resolved
blocks new capacity consumption
does NOT terminally suppress RecoveryJobs
RecoveryJob stays OPEN
```

## ACTIVE

```text
finalized provider unavailability
blocks destination booking
overlapping OPEN RecoveryJobs → SUPPRESSED
overlapping OFFERED SlotOffers → CANCELLED
```

## CANCELLED

```text
historical record
no longer blocks interval
```

Transitions currently required:

```text
PENDING → ACTIVE
PENDING → CANCELLED
```

Do not DELETE a cancelled pending block; UPDATE it to `CANCELLED`.

---

# 13. PENDING vs ACTIVE Integration Matrix

| Context | PENDING | ACTIVE |
|---|---|---|
| Direct booking destination | Block | Block |
| Direct reschedule destination | Block | Block |
| Offer acceptance destination | Block | Block |
| Scheduler reassignment W13B | Block | Block |
| Recovery worker W12 | Keep job OPEN; generate no offer | SUPPRESS job |
| Cancellation releasing old interval | Succeeds; RecoveryJob may be created | Succeeds; no RecoveryJob |
| Reschedule releasing old interval | Same | Same |
| Offer acceptance releasing old interval | Same | Same |

---

# 14. Workflow List

There are 14 locked workflow categories:

1. Direct booking
2. Normal appointment cancellation
3. Direct reschedule
4. Offer acceptance
5. Offer decline
6. Offer expiry
7. WaitlistEntry creation
8. WaitlistEntry modification
9. WaitlistEntry explicit removal
10. Appointment completion
11. Appointment no-show
12. Recovery worker offer generation
13A. Scheduler accept on behalf
13B. Scheduler reassign to another patient
14. Provider-block request / activation

Do not redesign the list unless implementation reveals a genuine contradiction.

---

# 15. Workflow 4 — Offer Acceptance Critical Semantics

Offer acceptance is the most complex transaction.

Canonical lock flow:

```text
Provider(s sorted)
→ current/old Appointment
→ accepted RecoveryJob
→ affected WaitlistEntries sorted
→ affected SlotOffers sorted
```

### Accepted SlotOffer strict terminal handling

```text
ACCEPTED
→ 409 OFFER_ALREADY_ACCEPTED
→ no second state change / no second audit

DECLINED
→ 409

CANCELLED
→ 409

EXPIRED
→ 410

OFFERED but expires_at <= wall-clock after lock
→ OFFERED → EXPIRED
→ audit
→ COMMIT
→ return 410

OFFERED and unexpired
→ continue acceptance
```

Use wall-clock time after locking:

```text
clock_timestamp()
```

not transaction-start `CURRENT_TIMESTAMP`.

### SlotOffer cleanup categories

Acceptance must consider:

```text
A. accepted offer

B. other OFFERED offers for accepted WaitlistEntry

C. OFFERED offers belonging to sibling WaitlistEntries
   that are being REMOVED

D. other same-patient OFFERED offers
   from different WaitlistEntries/RecoveryJobs
   whose offered intervals overlap the new Appointment
```

Category D is same patient, not other patients.

Its query must use bind parameters such as:

```text
:old_appointment_id
```

not self-comparisons such as:

```text
current_appointment_id != current_appointment_id
```

### Acceptance state changes

Atomically:

```text
INSERT new earlier Appointment

old Appointment:
SCHEDULED → CANCELLED
reason = RESCHEDULED
replaced_by = new Appointment

accepted WaitlistEntry:
ACTIVE → FULFILLED

sibling ACTIVE WaitlistEntries anchored to old Appointment:
→ REMOVED

accepted SlotOffer:
OFFERED → ACCEPTED

cleanup OFFERED SlotOffers:
→ CANCELLED

accepted RecoveryJob:
OPEN → FILLED

conditionally create RecoveryJob for old/released Appointment
```

---

# 16. Acceptance Conflict Handling

## Provider interval already occupied

Because destination Provider is locked, proactively revalidate provider occupancy.

If interval has already been consumed:

```text
accepted SlotOffer → CANCELLED
reason = PROVIDER_INTERVAL_OCCUPIED

RecoveryJob → FILLED

audit
COMMIT
return 409
```

Use existing Workflow 4 locks; do not reacquire rows in reverse order.

## Patient overlap

There is intentionally no global patient mutex.

Application-level validation checks the patient's schedule, but PostgreSQL patient exclusion constraint is final defense.

If `patient_no_overlap` fires:

```text
main acceptance transaction ROLLBACK
↓
start small cleanup transaction
↓
lock RecoveryJob
→ lock SlotOffer
↓
if SlotOffer still OFFERED:
    → CANCELLED / PATIENT_SCHEDULE_CONFLICT
↓
preserve RecoveryJob's legitimate current state
↓
COMMIT
↓
return 409
```

Do not assume RecoveryJob is still OPEN during cleanup.

Classify exclusion failures by constraint name.

---

# 17. Reconciliation Semantics — W2/W3/W10/W11

Cleanup is lenient for legitimate race wins.

## WaitlistEntry

```text
ACTIVE
→ REMOVED

REMOVED
→ preserve / continue

unexpected FULFILLED
→ invariant violation / abort
```

## SlotOffer

```text
OFFERED
→ CANCELLED

DECLINED
→ preserve

EXPIRED
→ preserve

CANCELLED
→ preserve

ACCEPTED
→ unexpected invariant violation
→ abort parent transaction
→ application error / investigation
```

Do not silently treat ACCEPTED as harmless cleanup.

---

# 18. Workflow 12 — Recovery Worker Critical Semantics

Lock order:

```text
Provider
→ RecoveryJob
→ selected WaitlistEntry
```

RecoveryJob must be locked **before** terminal classification.

After locking Provider and RecoveryJob:

```text
interval already occupied
→ RecoveryJob → FILLED

ACTIVE ProviderUnavailability
→ RecoveryJob → SUPPRESSED

PENDING ProviderUnavailability
→ RecoveryJob stays OPEN
→ no offer generated
→ commit/no-op

lead-time closed
→ RecoveryJob → SUPPRESSED

no eligible untried candidate
→ RecoveryJob → EXHAUSTED

selected candidate became stale after selection
→ RecoveryJob stays OPEN
→ commit/no-op

valid candidate
→ create OFFERED SlotOffer
→ RecoveryJob remains OPEN
```

### Candidate exclusion is per patient per job

Correct rule:

```text
Patient Y has ever received ANY SlotOffer
from RecoveryJob X
→ Patient Y cannot be offered RecoveryJob X again
```

A historical offer to Patient A must not prevent offering Patient B.

### Candidate pre-filter

Initial ranking query should exclude permanently ineligible candidates using committed data:

```text
WaitlistEntry ACTIVE
anchor Appointment SCHEDULED
anchor belongs to same patient
appointment type compatible
date window compatible
patient active
no conflicting SCHEDULED Appointment
patient has never previously received this RecoveryJob
recovered appointment fully earlier than anchor appointment
```

The recovered interval must satisfy:

```text
released_end <= anchor_appointment.start_at
```

not merely:

```text
released_start < anchor_start
```

### Ranking

Business ranking:

```text
1. longest waiting / created_at FIFO
2. time-preference match
3. provider-preference match
4. deterministic ID tie-breaker
```

Do not sort raw preference values.

Time-preference matching must use **clinic-local time**:

```text
appointment_start AT TIME ZONE :clinic_timezone
```

---

# 19. Provider-Block Request Flow

Admin requests unavailability.

Transaction begins by locking Provider.

### No conflicting SCHEDULED Appointments

```text
INSERT ProviderUnavailability(status = ACTIVE)

discover affected OPEN RecoveryJobs
lock them sorted

discover their OFFERED SlotOffers
lock them sorted

RecoveryJobs → SUPPRESSED
SlotOffers → CANCELLED

audit
COMMIT
```

### Conflicting SCHEDULED Appointments exist

```text
INSERT ProviderUnavailability(status = PENDING)
audit request + conflicting Appointment IDs
COMMIT

return PENDING/conflict information
```

Once PENDING commits, destination booking/recovery processing is immediately blocked.

---

# 20. Worker / Scheduler Model

Use Spring scheduled jobs inside the modular monolith.

## Recovery worker

```text
poll OPEN RecoveryJobs
one candidate attempt per transaction
```

## Offer expiry worker

```text
poll OFFERED SlotOffers past expires_at
lock SlotOffer only
OFFERED → EXPIRED
audit
commit
```

Do not lock RecoveryJob from expiry worker.

Worker interval should be configuration-driven, approximately 30–60 seconds for MVP.

---

# 21. Frontend Scope

Backend correctness first. React comes later.

## Patient

```text
register/login/logout
view appointments
book
cancel
direct reschedule
create/modify/remove waitlist entry
view offers
accept/decline offer
```

## Receptionist

```text
select/search patient
book/cancel/reschedule on behalf of patient
manage offers
accept on behalf
scheduler reassignment
provider-block conflict resolution
```

## Provider

```text
view own schedule
request unavailability
```

## Admin

```text
manage providers
specialties
appointment types
scheduling policy
provider-unavailability requests
audit history
```

---

# 22. Notifications

MVP:

```text
in-app offer visibility/status only
```

Do not add Twilio, SendGrid, Kafka, or SMS infrastructure initially.

Design code so external notification delivery can be added later.

---

# 23. Performance Targets

These are targets to measure, not claims:

```text
normal API p95 < 500 ms
recovery matching around 100k candidate-scale dataset < 500 ms
offer acceptance target < 200 ms where practical
~1000 simulated-user load test
unexpected failure target < 1%
```

Do not place these on the resume as achieved metrics until measured.

---

# 24. Working Protocol

Claude / ChatGPT is the **primary implementation partner**.

The user acts mainly as terminal/IDE operator.

Expected loop:

```text
assistant decides next implementation step
↓
assistant writes code / exact patch / exact commands
↓
user runs command
↓
user pastes output
↓
assistant diagnoses and continues
```

Do not tell the user:

```text
"Implement the PostgreSQL schema."
"Create the Spring Boot services."
"Write the tests."
```

Those are assistant responsibilities.

If local execution is required, provide exact commands and say exactly what output is needed.

Avoid unnecessary confirmation questions when the next action is obvious.

---

# 25. Current Exact Stopping Point

Design baseline is complete.

The transaction corrections have been documented but need to be merged into the canonical v3 specification.

Current execution sequence:

```text
1. Apply finalized patches to SECTION_2C_V3_COMPLETE.md

2. Mechanical verification
   - verify merged file contains all corrections
   - fix merge errors immediately

3. Pressure-test race matrix
   - explicit transaction interleavings
   - verify final invariants
   - verify modeled lock order has no reverse-order cycles

4. If matrix passes:
   Phase 0 complete

5. Begin actual implementation
```

Do not reopen architecture unless these steps expose a concrete correctness contradiction.

---

# 26. Mechanical Verification Essentials

After patch merge verify at minimum:

## W12

```text
Provider → RecoveryJob → WaitlistEntry
occupied → FILLED
ACTIVE block → SUPPRESSED
PENDING block → OPEN/no offer
no candidate → EXHAUSTED
stale candidate → OPEN
previous offer check is per patient/job
released_end <= anchor_start
timezone-aware preference ranking
```

## W2/W3/W10/W11

```text
lenient reconciliation
ACCEPTED cleanup state = invariant violation
```

## W4

```text
specific 409/410 terminal behavior
Category D corrected
RecoveryJob → SlotOffer lock order
patient-overlap separate cleanup transaction
provider occupancy → FILLED
```

## ProviderUnavailability

```text
PENDING / ACTIVE / CANCELLED
PENDING creation exists
PENDING → CANCELLED uses UPDATE, not DELETE
```

## Destination booking workflows

```text
W1 / W3 / W4 / W13B
PENDING and ACTIVE both block destination
```

## Released capacity

```text
PENDING old interval → RecoveryJob may be created
ACTIVE old interval → no RecoveryJob
```

---

# 27. Race-Matrix Method

For every race scenario document:

```text
initial database state
T1 lock sequence/actions
T2 lock sequence/actions
serialization point
winner behavior
waiter/loser behavior
final database state
invariant preserved?
deadlock/reverse-lock possibility?
```

Important scenarios include:

1. accept A vs accept A
2. accept A vs decline A
3. accept A vs expiry
4. accept A vs accept B for same patient
5. cancellation vs reschedule
6. two bookings same provider/time
7. two bookings same patient/time
8. provider block request with conflicts
9. block activation vs recovery generation
10. block activation vs booking
11. recovery generation vs waitlist removal
12. acceptance vs sibling waitlist removal
13. stale selected worker candidate
14. permanently ineligible candidate excluded by pre-filter

Exactly one terminal transition wins on a single SlotOffer.

Exactly one parent state transition wins on a single Appointment.

If successful provider-block activation completes, affected RecoveryJobs end SUPPRESSED and actionable offers end CANCELLED.

---

# 28. Implementation Order After Phase 0

Once mechanical verification and race analysis pass:

```text
repository/project structure
↓
Java 21 + Spring Boot + Maven
↓
PostgreSQL + Flyway
↓
schema / enums / constraints / indexes
↓
entities + repositories
↓
simple transaction services
↓
critical transaction services
↓
Testcontainers concurrency tests
↓
REST APIs
↓
Spring Security + sessions + RBAC
↓
scheduled workers
↓
React + TypeScript frontend
↓
Docker Compose
↓
GitHub Actions
↓
AWS deployment
↓
observability / load testing
↓
README + interview documentation
```

---

# 29. Interview Claims — Current vs Future

## Safe now

> I modeled lock dependencies as a DAG and enforced canonical lock ordering to eliminate the reverse-order deadlock cycles identified in the transaction design. The system uses READ COMMITTED, explicit row locking, post-lock revalidation, and PostgreSQL constraints to preserve application invariants.

## After race-matrix design analysis

> I pressure-tested the transaction design using explicit concurrent interleaving scenarios and verified the expected final-state invariants.

## Only after real PostgreSQL/Testcontainers tests

> I validated the concurrency behavior against PostgreSQL using Testcontainers-based concurrent integration tests.

Do not claim unmeasured performance or unimplemented validation.

---

# 30. New Chat Starter

In a new chat inside the **Resume and Applications** project, send:

> **Continue Project 1 — Dynamic Appointment Recovery System.**
>
> Use `PROJECT_1_MASTER_HANDOFF.md` as the canonical context for this implementation continuation.
>
> Do not redesign the system from scratch.
>
> We are currently at:
>
> **Apply final patches → mechanical verification → race matrix → implementation.**
>
> You are the primary implementation partner. I will mainly execute commands you give me and paste the results back.
>
> Start from the current stopping point and proceed with the first concrete action.

---

# Final Rule

**Do not optimize the project for the number of technologies listed on a resume.**

Optimize for:

```text
correctness
real implementation
tests
measured behavior
deployability
ownership
interview explainability
```

Project 1 becomes valuable when the system is running and its concurrency guarantees are demonstrated—not when the specification becomes longer.
