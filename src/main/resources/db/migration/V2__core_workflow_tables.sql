CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE appointment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES users(id),
    provider_id BIGINT NOT NULL REFERENCES provider(id),
    appointment_type_id BIGINT NOT NULL REFERENCES appointment_type(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (
        status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')
    ),
    cancellation_reason VARCHAR(20) CHECK (
        cancellation_reason IN ('PATIENT_CANCELLED', 'STAFF_CANCELLED', 'RESCHEDULED')
    ),
    replaced_by_appointment_id BIGINT REFERENCES appointment(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT no_patient_overlap EXCLUDE USING gist (
        patient_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status = 'SCHEDULED'),
    CONSTRAINT no_provider_overlap EXCLUDE USING gist (
        provider_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status = 'SCHEDULED'),
    CONSTRAINT appointment_cancellation_reason_required CHECK (
        (status = 'CANCELLED' AND cancellation_reason IS NOT NULL)
        OR (status != 'CANCELLED' AND cancellation_reason IS NULL)
    ),
    CONSTRAINT appointment_reschedule_replacement_required CHECK (
        (cancellation_reason = 'RESCHEDULED' AND replaced_by_appointment_id IS NOT NULL)
        OR (
            cancellation_reason IS DISTINCT FROM 'RESCHEDULED'
            AND replaced_by_appointment_id IS NULL
        )
    ),
    CONSTRAINT appointment_cannot_replace_itself CHECK (replaced_by_appointment_id != id)
);

CREATE UNIQUE INDEX uq_appointment_replaced_by_appointment_id
    ON appointment (replaced_by_appointment_id)
    WHERE replaced_by_appointment_id IS NOT NULL;

CREATE TABLE provider_unavailability (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES provider(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED')),
    reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_provider_unavailability_provider_status
    ON provider_unavailability (provider_id, status);

CREATE TABLE recovery_job (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_appointment_id BIGINT NOT NULL UNIQUE REFERENCES appointment(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'FILLED', 'EXHAUSTED', 'SUPPRESSED')),
    filled_at TIMESTAMPTZ,
    suppression_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE waitlist_entry (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES users(id),
    current_appointment_id BIGINT NOT NULL REFERENCES appointment(id),
    appointment_type_id BIGINT NOT NULL REFERENCES appointment_type(id),
    preferred_provider_id BIGINT REFERENCES provider(id),
    earliest_appointment_date DATE NOT NULL,
    latest_appointment_date DATE NOT NULL,
    preferred_time_of_day VARCHAR(10) NOT NULL DEFAULT 'ANY' CHECK (
        preferred_time_of_day IN ('MORNING', 'AFTERNOON', 'ANY')
    ),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'FULFILLED', 'REMOVED')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT waitlist_entry_date_range_valid CHECK (
        earliest_appointment_date <= latest_appointment_date
    )
);

CREATE INDEX idx_waitlist_entry_status_created_at
    ON waitlist_entry (status, created_at);

CREATE INDEX idx_waitlist_entry_current_appointment_id
    ON waitlist_entry (current_appointment_id);

CREATE TABLE slot_offer (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recovery_job_id BIGINT NOT NULL REFERENCES recovery_job(id),
    waitlist_entry_id BIGINT NOT NULL REFERENCES waitlist_entry(id),
    status VARCHAR(20) NOT NULL DEFAULT 'OFFERED' CHECK (
        status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX one_offered_per_recovery
    ON slot_offer (recovery_job_id)
    WHERE status = 'OFFERED';

CREATE INDEX idx_slot_offer_waitlist_entry_id
    ON slot_offer (waitlist_entry_id);

CREATE INDEX idx_slot_offer_status_expires_at
    ON slot_offer (status, expires_at);

CREATE TABLE scheduling_policy (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    minimum_recovery_lead_minutes INTEGER NOT NULL DEFAULT 30,
    offer_duration_minutes INTEGER NOT NULL DEFAULT 10,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO scheduling_policy DEFAULT VALUES;

CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    old_values JSONB,
    new_values JSONB,
    actor_type VARCHAR(10) NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
    actor_user_id BIGINT REFERENCES users(id),
    reason VARCHAR(1000),
    performed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_log_actor_valid CHECK (
        (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
        OR (actor_type = 'USER' AND actor_user_id IS NOT NULL)
    )
);

CREATE INDEX idx_audit_log_entity_type_entity_id
    ON audit_log (entity_type, entity_id);
