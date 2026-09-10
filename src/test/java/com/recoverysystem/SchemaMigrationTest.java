package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SchemaMigrationTest {

    private static final AtomicLong UNIQUE_SEQUENCE = new AtomicLong();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15");

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void migrationCreatesExpectedReferenceTablesAndColumns() throws SQLException {
        Map<String, Set<String>> expectedColumns = Map.of(
                "users", Set.of(
                        "id", "email", "password_hash", "role", "display_name", "created_at", "updated_at"),
                "specialty", Set.of("id", "name", "description"),
                "appointment_type", Set.of(
                        "id", "name", "duration_minutes", "specialty_id", "description", "is_active"),
                "provider", Set.of(
                        "id", "user_id", "specialty_id", "license_number", "qualifications", "created_at", "updated_at"),
                "provider_schedule", Set.of(
                        "id", "provider_id", "day_of_week", "start_time", "end_time", "is_active"));

        try (Connection connection = openConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();

            for (Map.Entry<String, Set<String>> entry : expectedColumns.entrySet()) {
                String table = entry.getKey();
                assertTrue(tableExists(metadata, table), () -> "Expected table to exist: " + table);
                assertEquals(entry.getValue(), columnsFor(metadata, table),
                        () -> "Unexpected columns for table: " + table);
            }
        }
    }

    @Test
    void specialtyNameMustBeUnique() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO specialty (name) VALUES (?)")) {
            insert.setString(1, "Cardiology");
            insert.executeUpdate();

            insert.setString(1, "Cardiology");
            assertThrows(SQLException.class, insert::executeUpdate);
        }
    }

    @Test
    void usersRoleRejectsInvalidValue() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?)")) {
            insert.setString(1, "invalid-role@example.com");
            insert.setString(2, "test-password-hash");
            insert.setString(3, "SUPERADMIN");

            assertThrows(SQLException.class, insert::executeUpdate);
        }
    }

    @Test
    void providerUserIdMustReferenceExistingUser() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            long specialtyId;
            try (ResultSet result = statement.executeQuery(
                    "INSERT INTO specialty (name) VALUES ('Neurology') RETURNING id")) {
                assertTrue(result.next());
                specialtyId = result.getLong("id");
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO provider (user_id, specialty_id, license_number) VALUES (?, ?, ?)")) {
                insert.setLong(1, Long.MAX_VALUE);
                insert.setLong(2, specialtyId);
                insert.setString(3, "TEST-LICENSE");

                assertThrows(SQLException.class, insert::executeUpdate);
            }
        }
    }

    @Test
    void migrationCreatesCoreWorkflowTablesWithPrimaryKeys() throws SQLException {
        Set<String> expectedTables = Set.of(
                "appointment",
                "provider_unavailability",
                "recovery_job",
                "waitlist_entry",
                "slot_offer",
                "scheduling_policy",
                "audit_log");

        try (Connection connection = openConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : expectedTables) {
                assertTrue(tableExists(metadata, table), () -> "Expected table to exist: " + table);
                assertEquals(Set.of("id"), primaryKeyColumnsFor(metadata, table),
                        () -> "Unexpected primary key for table: " + table);
            }
        }
    }

    @Test
    void appointmentHasBothExclusionConstraints() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT conname
                     FROM pg_constraint
                     WHERE conrelid = 'appointment'::regclass
                       AND contype = 'x'
                     """)) {
            Set<String> constraintNames = new HashSet<>();
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    constraintNames.add(result.getString("conname"));
                }
            }

            assertEquals(Set.of("no_patient_overlap", "no_provider_overlap"), constraintNames);
        }
    }

    @Test
    void btreeGistExtensionIsInstalled() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'");
             ResultSet result = query.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void patientOverlapExclusionIsEnforced() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);
            insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-01-10T12:00:00Z",
                    "2030-01-10T13:00:00Z");

            SQLException violation = assertThrows(SQLException.class, () -> insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.secondProviderId(),
                    data.appointmentTypeId(),
                    "2030-01-10T12:30:00Z",
                    "2030-01-10T13:30:00Z"));

            assertEquals("23P01", violation.getSQLState());
            assertTrue(violation.getMessage().contains("no_patient_overlap"));
        }
    }

    @Test
    void providerOverlapExclusionIsEnforced() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);
            insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-02-10T12:00:00Z",
                    "2030-02-10T13:00:00Z");

            SQLException violation = assertThrows(SQLException.class, () -> insertScheduledAppointment(
                    connection,
                    data.secondPatientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-02-10T12:30:00Z",
                    "2030-02-10T13:30:00Z"));

            assertEquals("23P01", violation.getSQLState());
            assertTrue(violation.getMessage().contains("no_provider_overlap"));
        }
    }

    @Test
    void adjacentScheduledAppointmentsDoNotOverlap() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);

            long firstAppointmentId = insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-03-10T12:00:00Z",
                    "2030-03-10T13:00:00Z");
            long secondAppointmentId = insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-03-10T13:00:00Z",
                    "2030-03-10T14:00:00Z");

            assertTrue(firstAppointmentId > 0);
            assertTrue(secondAppointmentId > 0);
        }
    }

    @Test
    void providerUnavailabilityAcceptsValidStatusesAndRejectsInvalidStatus() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);
            for (String status : Set.of("PENDING", "ACTIVE", "CANCELLED")) {
                assertEquals(1, insertProviderUnavailability(connection, data.providerId(), status));
            }

            SQLException violation = assertThrows(SQLException.class,
                    () -> insertProviderUnavailability(connection, data.providerId(), "INVALID"));
            assertEquals("23514", violation.getSQLState());
        }
    }

    @Test
    void recoveryJobSourceAppointmentMustBeUnique() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);
            long appointmentId = insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-04-10T12:00:00Z",
                    "2030-04-10T13:00:00Z");

            assertTrue(insertRecoveryJob(connection, appointmentId) > 0);
            SQLException violation = assertThrows(
                    SQLException.class, () -> insertRecoveryJob(connection, appointmentId));
            assertEquals("23505", violation.getSQLState());
        }
    }

    @Test
    void onlyOneOfferedSlotOfferIsAllowedPerRecoveryJob() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);
            long sourceAppointmentId = insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-05-10T12:00:00Z",
                    "2030-05-10T13:00:00Z");
            long currentAppointmentId = insertScheduledAppointment(
                    connection,
                    data.patientId(),
                    data.providerId(),
                    data.appointmentTypeId(),
                    "2030-05-11T12:00:00Z",
                    "2030-05-11T13:00:00Z");
            long recoveryJobId = insertRecoveryJob(connection, sourceAppointmentId);
            long firstWaitlistEntryId = insertWaitlistEntry(
                    connection, data.patientId(), currentAppointmentId, data.appointmentTypeId());
            long secondWaitlistEntryId = insertWaitlistEntry(
                    connection, data.patientId(), currentAppointmentId, data.appointmentTypeId());

            assertTrue(insertSlotOffer(connection, recoveryJobId, firstWaitlistEntryId, "OFFERED") > 0);
            SQLException violation = assertThrows(SQLException.class,
                    () -> insertSlotOffer(connection, recoveryJobId, secondWaitlistEntryId, "OFFERED"));
            assertEquals("23505", violation.getSQLState());

            assertTrue(insertSlotOffer(connection, recoveryJobId, secondWaitlistEntryId, "DECLINED") > 0);
        }
    }

    @Test
    void schedulingPolicyHasExactlyOneDefaultRow() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM scheduling_policy")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void auditLogActorRulesAreEnforced() throws SQLException {
        try (Connection connection = openConnection()) {
            ReferenceData data = createReferenceData(connection);

            SQLException systemViolation = assertThrows(SQLException.class, () -> insertAuditLog(
                    connection, "SYSTEM", data.patientId()));
            assertEquals("23514", systemViolation.getSQLState());

            SQLException userViolation = assertThrows(
                    SQLException.class, () -> insertAuditLog(connection, "USER", null));
            assertEquals("23514", userViolation.getSQLState());
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws SQLException {
        try (ResultSet tables = metadata.getTables(null, "public", table, new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    private static Set<String> columnsFor(DatabaseMetaData metadata, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet result = metadata.getColumns(null, "public", table, null)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static Set<String> primaryKeyColumnsFor(DatabaseMetaData metadata, String table)
            throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet result = metadata.getPrimaryKeys(null, "public", table)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static ReferenceData createReferenceData(Connection connection) throws SQLException {
        String suffix = Long.toString(UNIQUE_SEQUENCE.incrementAndGet());
        long patientId = insertUser(connection, "patient-" + suffix + "@example.com", "PATIENT");
        long secondPatientId = insertUser(
                connection, "second-patient-" + suffix + "@example.com", "PATIENT");
        long firstProviderUserId = insertUser(
                connection, "provider-" + suffix + "@example.com", "PROVIDER");
        long secondProviderUserId = insertUser(
                connection, "second-provider-" + suffix + "@example.com", "PROVIDER");
        long specialtyId = insertReturningId(
                connection,
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                "Specialty " + suffix);
        long appointmentTypeId = insertReturningId(
                connection,
                """
                        INSERT INTO appointment_type (name, duration_minutes, specialty_id)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                "Appointment Type " + suffix,
                60,
                specialtyId);
        long providerId = insertProvider(
                connection, firstProviderUserId, specialtyId, "LICENSE-" + suffix);
        long secondProviderId = insertProvider(
                connection, secondProviderUserId, specialtyId, "SECOND-LICENSE-" + suffix);

        return new ReferenceData(
                patientId, secondPatientId, providerId, secondProviderId, appointmentTypeId);
    }

    private static long insertUser(Connection connection, String email, String role) throws SQLException {
        return insertReturningId(
                connection,
                """
                        INSERT INTO users (email, password_hash, role)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                email,
                "test-password-hash",
                role);
    }

    private static long insertProvider(
            Connection connection, long userId, long specialtyId, String licenseNumber)
            throws SQLException {
        return insertReturningId(
                connection,
                """
                        INSERT INTO provider (user_id, specialty_id, license_number)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                userId,
                specialtyId,
                licenseNumber);
    }

    private static long insertScheduledAppointment(
            Connection connection,
            long patientId,
            long providerId,
            long appointmentTypeId,
            String startAt,
            String endAt) throws SQLException {
        return insertReturningId(
                connection,
                """
                        INSERT INTO appointment (
                            patient_id, provider_id, appointment_type_id, start_at, end_at, status
                        )
                        VALUES (?, ?, ?, ?, ?, 'SCHEDULED')
                        RETURNING id
                        """,
                patientId,
                providerId,
                appointmentTypeId,
                OffsetDateTime.parse(startAt),
                OffsetDateTime.parse(endAt));
    }

    private static int insertProviderUnavailability(
            Connection connection, long providerId, String status) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO provider_unavailability (provider_id, start_at, end_at, status)
                VALUES (?, '2030-06-10T12:00:00Z', '2030-06-10T13:00:00Z', ?)
                """)) {
            insert.setLong(1, providerId);
            insert.setString(2, status);
            return insert.executeUpdate();
        }
    }

    private static long insertRecoveryJob(Connection connection, long appointmentId)
            throws SQLException {
        return insertReturningId(
                connection,
                """
                        INSERT INTO recovery_job (source_appointment_id, status)
                        VALUES (?, 'OPEN')
                        RETURNING id
                        """,
                appointmentId);
    }

    private static long insertWaitlistEntry(
            Connection connection, long patientId, long currentAppointmentId, long appointmentTypeId)
            throws SQLException {
        return insertReturningId(
                connection,
                """
                        INSERT INTO waitlist_entry (
                            patient_id,
                            current_appointment_id,
                            appointment_type_id,
                            earliest_appointment_date,
                            latest_appointment_date
                        )
                        VALUES (?, ?, ?, '2030-05-01', '2030-05-31')
                        RETURNING id
                        """,
                patientId,
                currentAppointmentId,
                appointmentTypeId);
    }

    private static long insertSlotOffer(
            Connection connection, long recoveryJobId, long waitlistEntryId, String status)
            throws SQLException {
        return insertReturningId(
                connection,
                """
                        INSERT INTO slot_offer (
                            recovery_job_id, waitlist_entry_id, status, expires_at
                        )
                        VALUES (?, ?, ?, '2030-05-10T12:10:00Z')
                        RETURNING id
                        """,
                recoveryJobId,
                waitlistEntryId,
                status);
    }

    private static void insertAuditLog(
            Connection connection, String actorType, Long actorUserId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO audit_log (entity_type, entity_id, action, actor_type, actor_user_id)
                VALUES ('APPOINTMENT', 1, 'CREATE', ?, ?)
                """)) {
            insert.setString(1, actorType);
            if (actorUserId == null) {
                insert.setNull(2, java.sql.Types.BIGINT);
            } else {
                insert.setLong(2, actorUserId);
            }
            insert.executeUpdate();
        }
    }

    private static long insertReturningId(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "Expected INSERT to return an id");
                return result.getLong("id");
            }
        }
    }

    private record ReferenceData(
            long patientId,
            long secondPatientId,
            long providerId,
            long secondProviderId,
            long appointmentTypeId) {
    }
}
