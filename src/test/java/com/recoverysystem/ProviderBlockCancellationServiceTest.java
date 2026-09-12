package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.recoverysystem.domain.entity.AuditLog;
import com.recoverysystem.domain.entity.ProviderUnavailability;
import com.recoverysystem.domain.enums.ActorType;
import com.recoverysystem.domain.enums.ProviderUnavailabilityStatus;
import com.recoverysystem.exception.ProviderBlockNotPendingException;
import com.recoverysystem.exception.ProviderUnavailabilityNotFoundException;
import com.recoverysystem.repository.AuditLogRepository;
import com.recoverysystem.repository.ProviderUnavailabilityRepository;
import com.recoverysystem.service.ProviderBlockCancellationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ProviderBlockCancellationServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private ProviderBlockCancellationService providerBlockCancellationService;

    @Autowired
    private ProviderUnavailabilityRepository providerUnavailabilityRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTestData() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_log, provider_unavailability, "
                + "provider, specialty, users RESTART IDENTITY CASCADE");
    }

    @Test
    void pendingBlockIsCancelledWithReasonAndOneUserAudit() {
        Long providerId = insertProvider();
        Long actorUserId = insertUser("block-cancellation-receptionist", "RECEPTIONIST");
        Long blockId = insertBlock(
                providerId, ProviderUnavailabilityStatus.PENDING, "Original block reason");
        long auditMarker = latestAuditId();

        ProviderUnavailability result = providerBlockCancellationService.cancelPendingBlock(
                blockId, "Schedule conflict was resolved", actorUserId);

        ProviderUnavailability savedBlock =
                providerUnavailabilityRepository.findById(blockId).orElseThrow();
        assertEquals(ProviderUnavailabilityStatus.CANCELLED, result.getStatus());
        assertEquals(ProviderUnavailabilityStatus.CANCELLED, savedBlock.getStatus());
        assertNotNull(savedBlock.getCancelledAt());
        assertEquals(0, savedBlock.getCancelledAt().getNano() % 1_000);
        assertEquals("Original block reason", savedBlock.getReason());

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(1, audits.size());
        assertCancelAudit(
                audits.getFirst(),
                blockId,
                "Schedule conflict was resolved",
                ActorType.USER,
                actorUserId);
    }

    @Test
    void activeBlockCannotBeCancelled() {
        Long blockId = insertBlock(
                insertProvider(), ProviderUnavailabilityStatus.ACTIVE, "Active block reason");
        BlockSnapshot beforeCall = captureBlock(blockId);
        long auditMarker = latestAuditId();

        ProviderBlockNotPendingException exception = assertThrows(
                ProviderBlockNotPendingException.class,
                () -> providerBlockCancellationService.cancelPendingBlock(
                        blockId, "Should not be used", null));

        assertTrue(exception.getMessage().contains("ACTIVE"));
        assertBlockUnchanged(blockId, beforeCall);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void cancelledBlockCannotBeCancelledAgain() {
        Long blockId = insertBlock(
                insertProvider(), ProviderUnavailabilityStatus.CANCELLED, "Cancelled block reason");
        BlockSnapshot beforeCall = captureBlock(blockId);
        long auditMarker = latestAuditId();

        ProviderBlockNotPendingException exception = assertThrows(
                ProviderBlockNotPendingException.class,
                () -> providerBlockCancellationService.cancelPendingBlock(
                        blockId, "Second cancellation", null));

        assertTrue(exception.getMessage().contains("CANCELLED"));
        assertBlockUnchanged(blockId, beforeCall);
        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void nonexistentBlockIsRejected() {
        long auditMarker = latestAuditId();

        assertThrows(
                ProviderUnavailabilityNotFoundException.class,
                () -> providerBlockCancellationService.cancelPendingBlock(
                        Long.MAX_VALUE, "Missing block", null));

        assertTrue(auditsAfter(auditMarker).isEmpty());
    }

    @Test
    void nullAndNonNullActorsCreateSystemAndUserAudits() {
        Long providerId = insertProvider();
        Long systemBlockId = insertBlock(
                providerId, ProviderUnavailabilityStatus.PENDING, "System block");
        Long userBlockId = insertBlock(
                providerId, ProviderUnavailabilityStatus.PENDING, "User block");
        Long actorUserId = insertUser("block-cancellation-user", "RECEPTIONIST");
        long auditMarker = latestAuditId();

        providerBlockCancellationService.cancelPendingBlock(systemBlockId, "System action", null);
        providerBlockCancellationService.cancelPendingBlock(
                userBlockId, "User action", actorUserId);

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(2, audits.size());
        assertCancelAudit(
                findAudit(audits, systemBlockId),
                systemBlockId,
                "System action",
                ActorType.SYSTEM,
                null);
        assertCancelAudit(
                findAudit(audits, userBlockId),
                userBlockId,
                "User action",
                ActorType.USER,
                actorUserId);
    }

    @Test
    void nullCancellationReasonIsAllowed() {
        Long blockId = insertBlock(
                insertProvider(), ProviderUnavailabilityStatus.PENDING, "Original reason");
        long auditMarker = latestAuditId();

        providerBlockCancellationService.cancelPendingBlock(blockId, null, null);

        List<AuditLog> audits = auditsAfter(auditMarker);
        assertEquals(1, audits.size());
        assertNull(audits.getFirst().getReason());
        assertEquals(
                ProviderUnavailabilityStatus.CANCELLED,
                providerUnavailabilityRepository.findById(blockId).orElseThrow().getStatus());
    }

    @Test
    void serviceDoesNotImportProviderRecoveryJobOrSlotOfferRepositories() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/recoverysystem/service/ProviderBlockCancellationService.java"));

        assertFalse(source.contains("import com.recoverysystem.repository.ProviderRepository;"));
        assertFalse(source.contains("import com.recoverysystem.repository.RecoveryJobRepository;"));
        assertFalse(source.contains("import com.recoverysystem.repository.SlotOfferRepository;"));
    }

    private Long insertProvider() {
        long number = SEQUENCE.incrementAndGet();
        Long specialtyId = jdbcTemplate.queryForObject(
                "INSERT INTO specialty (name) VALUES (?) RETURNING id",
                Long.class,
                "Cancellation Specialty " + number);
        Long providerUserId = insertUser("block-cancellation-provider-" + number, "PROVIDER");
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider (user_id, specialty_id, license_number) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                providerUserId,
                specialtyId,
                "BLOCK-CANCELLATION-" + number);
    }

    private Long insertUser(String emailPrefix, String role) {
        long number = SEQUENCE.incrementAndGet();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                emailPrefix + "-" + number + "@example.com",
                "test-password-hash",
                role);
    }

    private Long insertBlock(
            Long providerId, ProviderUnavailabilityStatus status, String reason) {
        Instant startAt = Instant.parse("2065-01-10T14:00:00Z")
                .plusSeconds(SEQUENCE.incrementAndGet() * 7200);
        Instant activatedAt = status == ProviderUnavailabilityStatus.ACTIVE ? startAt : null;
        Instant cancelledAt = status == ProviderUnavailabilityStatus.CANCELLED ? startAt : null;
        return jdbcTemplate.queryForObject(
                "INSERT INTO provider_unavailability "
                        + "(provider_id, start_at, end_at, status, reason, activated_at, "
                        + "cancelled_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                providerId,
                Timestamp.from(startAt),
                Timestamp.from(startAt.plusSeconds(3600)),
                status.name(),
                reason,
                activatedAt == null ? null : Timestamp.from(activatedAt),
                cancelledAt == null ? null : Timestamp.from(cancelledAt));
    }

    private BlockSnapshot captureBlock(Long blockId) {
        ProviderUnavailability block =
                providerUnavailabilityRepository.findById(blockId).orElseThrow();
        return new BlockSnapshot(
                block.getStatus(),
                block.getReason(),
                block.getActivatedAt(),
                block.getCancelledAt(),
                block.getUpdatedAt());
    }

    private void assertBlockUnchanged(Long blockId, BlockSnapshot beforeCall) {
        ProviderUnavailability block =
                providerUnavailabilityRepository.findById(blockId).orElseThrow();
        assertEquals(beforeCall.status(), block.getStatus());
        assertEquals(beforeCall.reason(), block.getReason());
        assertEquals(beforeCall.activatedAt(), block.getActivatedAt());
        assertEquals(beforeCall.cancelledAt(), block.getCancelledAt());
        assertEquals(beforeCall.updatedAt(), block.getUpdatedAt());
    }

    private void assertCancelAudit(
            AuditLog audit,
            Long blockId,
            String reason,
            ActorType actorType,
            Long actorUserId) {
        assertEquals("ProviderUnavailability", audit.getEntityType());
        assertEquals(blockId, audit.getEntityId());
        assertEquals("CANCEL", audit.getAction());
        assertEquals(reason, audit.getReason());
        assertEquals(actorType, audit.getActorType());
        assertEquals(actorUserId, audit.getActorUserId());
    }

    private AuditLog findAudit(List<AuditLog> audits, Long blockId) {
        return audits.stream()
                .filter(audit -> blockId.equals(audit.getEntityId()))
                .findFirst()
                .orElseThrow();
    }

    private long latestAuditId() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
    }

    private List<AuditLog> auditsAfter(long auditId) {
        return auditLogRepository.findAll().stream()
                .filter(audit -> audit.getId() > auditId)
                .toList();
    }

    private record BlockSnapshot(
            ProviderUnavailabilityStatus status,
            String reason,
            Instant activatedAt,
            Instant cancelledAt,
            Instant updatedAt) {
    }
}
