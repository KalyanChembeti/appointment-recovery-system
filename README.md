# Dynamic Appointment Recovery System

Portfolio backend project. Modular monolith that recovers unused outpatient appointment
capacity: when an appointment is cancelled, a durable `RecoveryJob` is created, eligible
waitlisted patients are ranked, and a time-limited `SlotOffer` is issued and safely resolved
under concurrent access.

Design context: `docs/PROJECT_1_MASTER_HANDOFF.md` and `docs/DOCUMENT_INVENTORY.md`.
Phase 0 (domain model, state machines, transaction/concurrency design, 13+ scenario race-matrix
validation) is locked. This repo is Phase 1 implementation.

## Stack

- Java 21, Spring Boot 3.3.4, Maven
- PostgreSQL 15, Flyway
- Spring Security (server-side sessions, HttpOnly cookies, RBAC — no JWT)
- JUnit 5, Testcontainers
- React + TypeScript + Vite (added later, backend-first)

## Local setup

Prerequisites: Java 21, Maven, Docker.

```bash
# 1. Start Postgres
docker compose up -d

# 2. Verify it's healthy
docker compose ps

# 3. Build (runs Flyway migrations against the schema once they exist)
mvn clean install

# 4. Run
mvn spring-boot:run
```

App starts on `http://localhost:8080`.

## Project structure

```
src/main/java/com/recoverysystem/
  config/       - Spring configuration (security, scheduling, etc.)
  domain/
    entity/     - JPA entities (12 domain entities)
    enums/      - State machine enums (Appointment, WaitlistEntry, RecoveryJob, SlotOffer, etc.)
  repository/   - Spring Data JPA repositories
  service/      - Transactional workflow services (the 14 locked workflows)
  web/
    controller/ - REST controllers
    dto/        - Request/response DTOs
  security/     - Session auth, RBAC
  worker/       - Spring @Scheduled recovery + offer-expiry workers
  exception/    - Exception types + handlers

src/main/resources/
  db/migration/ - Flyway migrations (schema is the source of truth for constraints)
  application.yml
```

## Status

Phase 1, Step 1-2: repo structure + Spring Boot/Maven bootstrap. Schema and entities not yet
implemented.
