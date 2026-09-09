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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SchemaMigrationTest {

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
}
