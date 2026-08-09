package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.runtime
        .HytaleBondedCompanionWorldGateway;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.bson.BsonDocument;
import com.alechilles.alecstamework.npc.components
        .TameworkProjectionIdentityComponent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the isolated bonded runtime, publication order, and teardown seam. */
class BondedCompanionCompositionTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000005"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsAndServesReadsWithoutAnyGenericPersistenceRuntime() {
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> -5_000L
        );
        try {
            assertTrue(composition.api().availability().available());
            var listed = composition.api().list(OWNER, "hydragon:dragons")
                    .join();
            assertEquals(BondedCompanionResultCode.SUCCESS, listed.code());
            assertEquals(0, listed.value().size());
            assertEquals("READY", composition.diagnostics().snapshot().readiness());
            assertEquals(
                    BondedCompanionSchemaManager.VERSION,
                    composition.diagnostics().snapshot().schemaVersion()
            );
        } finally {
            composition.close();
        }

        assertFalse(composition.api().availability().available());
        assertEquals("CLOSED", composition.diagnostics().snapshot().readiness());
        composition.close();
    }

    @Test
    void captureAndPanelAbsenceDoesNotDisableCoreBondedAuthority() {
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> -5_000L
        );
        try {
            assertTrue(composition.api().availability().available());
            assertEquals(BondedCompanionResultCode.SUCCESS,
                    composition.api().list(OWNER, "hydragon:dragons")
                            .join().code());
        } finally {
            composition.close();
        }
    }

    @Test
    void maintenancePrunesFiniteTerminalOperationsButRetainsPinnedPayments()
            throws Exception {
        long now = 10_000L;
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> now
                );
        Path database = temporaryDirectory.toAbsolutePath().normalize()
                .resolve(BondedCompanionDataPath.FILE_NAME);
        try {
            insertTerminalOperation(
                    database, "expired-operation", "SUCCEEDED", now - 1L);
            insertTerminalOperation(
                    database, "pinned-payment", "REJECTED", Long.MAX_VALUE);

            composition.maintenanceTick();

            assertEquals(0L, operationCount(
                    database, "expired-operation"));
            assertEquals(1L, operationCount(database, "pinned-payment"));
        } finally {
            composition.close();
        }
    }

    /** Regression: a replaced bonded database must not escape a world tick. */
    @Test
    void maintenanceFailsClosedAndReportsReplacedDatabaseOnce() throws Exception {
        ArrayList<BondedCompanionStorageFailureEvidence> failures =
                new ArrayList<>();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> 10_000L,
                        null,
                        failures::add
                );
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        try {
            replaceDatabase(database);

            assertDoesNotThrow(() -> composition.maintenanceTick());
            assertDoesNotThrow(() -> composition.maintenanceTick());

            assertFalse(composition.api().availability().available());
            assertEquals("bonded-runtime-storage-failed",
                    composition.api().availability().reason());
            assertEquals(1, failures.size());
            assertEquals("maintenance", failures.getFirst().operation());
            assertEquals("present", failures.getFirst().baselineFileState());
            assertEquals("present", failures.getFirst().failureFileState());
            assertEquals("decreased", failures.getFirst().sizeComparison());
        } finally {
            composition.close();
        }
    }

    /** Regression: expiry-warning lease reads must fail closed on schema loss. */
    @Test
    void activeLeaseReadFailsClosedAndReportsReplacedDatabaseOnce()
            throws Exception {
        ArrayList<BondedCompanionStorageFailureEvidence> failures =
                new ArrayList<>();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> 10_000L,
                        null,
                        failures::add
                );
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        try {
            replaceDatabase(database);

            assertDoesNotThrow(() -> assertTrue(composition
                    .activeLeasesInWorld("world-a", 64).isEmpty()));
            assertDoesNotThrow(() -> composition.maintenanceTick());

            assertFalse(composition.api().availability().available());
            assertEquals(1, failures.size());
            assertEquals("active_lease_read",
                    failures.getFirst().operation());
        } finally {
            composition.close();
        }
    }

    /** Regression: public API reads must disable a replaced database session. */
    @Test
    void apiReadFailsClosedAndReportsReplacedDatabaseOnce() throws Exception {
        ArrayList<BondedCompanionStorageFailureEvidence> failures =
                new ArrayList<>();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> 10_000L,
                        null,
                        failures::add
                );
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        try {
            replaceDatabase(database);

            composition.api().list(UUID.randomUUID(), "default").join();

            assertFalse(composition.api().availability().available());
            assertEquals(1, failures.size());
            assertEquals("api_read", failures.getFirst().operation());
        } finally {
            composition.close();
        }
    }

    /** Regression: swallowed logout lease-read failures must disable persistence. */
    @Test
    void logoutFailsClosedWhenLeaseReaderSwallowsDatabaseFailure()
            throws Exception {
        ArrayList<BondedCompanionStorageFailureEvidence> failures =
                new ArrayList<>();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(),
                        null,
                        () -> 10_000L,
                        null,
                        failures::add
                );
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        UUID owner = UUID.randomUUID();
        try {
            composition.onPlayerAdded(owner, "world-a");
            replaceDatabase(database);

            assertDoesNotThrow(() -> composition.onPlayerLogout(owner));

            assertFalse(composition.api().availability().available());
            assertEquals(1, failures.size());
            assertEquals("owner_world_lease_read",
                    failures.getFirst().operation());
        } finally {
            composition.close();
        }
    }

    /** Regression: converted SQLite write failures must still close the session. */
    @Test
    void apiMutationFailsClosedWhenStoreConvertsSqlExceptionToResult()
            throws Exception {
        ArrayList<BondedCompanionStorageFailureEvidence> failures =
                new ArrayList<>();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, rosterRegistry(), null,
                        () -> 10_000L, null, failures::add
                );
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        try {
            try (Connection blocker = new SqliteConnectionFactory(database)
                    .openWriterConnection();
                 var statement = blocker.createStatement()) {
                statement.execute("BEGIN EXCLUSIVE");
                composition.api().provision(
                        new BondedCompanionProvisionRequest(
                                "test", "failed-write", OWNER,
                                "hydragon:dragons", "Tamed_Dragon_Fire",
                                "Ember", "Dragon", "Female", Map.of()
                        )).join();
                statement.execute("ROLLBACK");
            }

            assertFalse(composition.api().availability().available());
            assertEquals(1, failures.size());
            assertEquals("operation_write", failures.getFirst().operation());
        } finally {
            composition.close();
        }
    }

    @Test
    void provisionAndExtensionMutationsAreRealIdempotentAndPublished()
            throws Exception {
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, rosters, null, () -> -5_000L
                );
        AtomicInteger changes = new AtomicInteger();
        AutoCloseable subscription = composition.api().subscribe(
                ignored -> changes.incrementAndGet()
        );
        try {
            BondedCompanionProvisionRequest request =
                    new BondedCompanionProvisionRequest(
                            "test", "provision-1", OWNER,
                            "hydragon:dragons", "Tamed_Dragon_Fire",
                            "Ember", "Dragon", "Female",
                            Map.of("variant", "ember")
                    );

            var created = composition.api().provision(request).join();
            var replay = composition.api().provision(request).join();
            var conflictingReplay = composition.api().provision(
                    new BondedCompanionProvisionRequest(
                            "test", "provision-1", OWNER,
                            "hydragon:dragons", "Tamed_Dragon_Fire",
                            "Not Ember", "Dragon", "Female",
                            Map.of("variant", "ember")
                    )
            ).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, created.code());
            assertEquals(BondedCompanionResultCode.SUCCESS, replay.code());
            assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                    conflictingReplay.code());
            assertEquals(created.value().profileId(), replay.value().profileId());
            assertEquals(1, changes.get());

            BondedCompanionExtensionDataKey key =
                    new BondedCompanionExtensionDataKey(
                            OWNER, created.value().profileId(), "hydragon.combat"
                    );
            var extension = composition.api().compareAndSetExtensionData(
                    new BondedCompanionExtensionDataUpdate(
                            "test", "extension-1", key,
                            "{\"stance\":\"guard\"}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION
                    )
            ).join();
            assertEquals(BondedCompanionResultCode.SUCCESS, extension.code());
            assertEquals(extension.value(), composition.api()
                    .getExtensionData(key).join().value());
        } finally {
            subscription.close();
            composition.close();
        }
    }

    @Test
    void sharedRosterProvisionsAndListsFamiliesWithIndependentOwnedLimits()
            throws Exception {
        BondedCompanionRosterRegistry rosters = sharedRosterRegistry(false);
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, rosters, null, () -> 5_000L
                );
        try {
            var dragon = composition.api().provision(provision(
                    "dragon-1", "Tamed_Dragon_Fire", "hydragon:dragon"
            )).join();
            var miniwyvern = composition.api().provision(provision(
                    "mini-1", "Bonded_Miniwyvern", "hydragon:miniwyvern"
            )).join();
            var secondDragon = composition.api().provision(provision(
                    "dragon-2", "Tamed_Dragon_Fire", "hydragon:dragon"
            )).join();
            var secondMiniwyvern = composition.api().provision(provision(
                    "mini-2", "Bonded_Miniwyvern", "hydragon:miniwyvern"
            )).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, dragon.code());
            assertEquals(BondedCompanionResultCode.SUCCESS, miniwyvern.code());
            assertEquals(BondedCompanionResultCode.POLICY_DENIED,
                    secondDragon.code());
            assertEquals(BondedCompanionResultCode.POLICY_DENIED,
                    secondMiniwyvern.code());
            var listed = composition.api().list(OWNER, "hydragon:horn").join();
            assertEquals(BondedCompanionResultCode.SUCCESS, listed.code());
            assertEquals(2, listed.value().size());
            assertEquals(
                    Set.of("hydragon:dragon", "hydragon:miniwyvern"),
                    listed.value().stream()
                            .map(view -> view.familyId())
                            .collect(java.util.stream.Collectors.toSet())
            );
            String miniProfileId = listed.value().stream()
                    .filter(view -> "hydragon:miniwyvern".equals(view.familyId()))
                    .findFirst().orElseThrow().profileId();
            BondedCompanionExtensionDataKey extensionKey =
                    new BondedCompanionExtensionDataKey(
                            OWNER, miniProfileId, "hydragon.abilities");
            var updated = composition.api().compareAndSetExtensionData(
                    new BondedCompanionExtensionDataUpdate(
                            "test", "mini-extension", extensionKey,
                            "{\"attuned\":true}",
                            BondedCompanionExtensionDataUpdate.MISSING_REVISION
                    )).join();
            assertEquals(BondedCompanionResultCode.SUCCESS, updated.code());
            assertEquals(updated.value(), composition.api()
                    .getExtensionData(extensionKey).join().value());
            assertTrue(rosters.replace(
                    List.of(), rosters.snapshot().revision() + 1L
            ).applied());
            assertEquals(updated.value(), composition.api()
                    .getExtensionData(extensionKey).join().value());
            var otherOwner = composition.api().getExtensionData(
                    new BondedCompanionExtensionDataKey(
                            UUID.fromString(
                                    "10000000-0000-0000-0000-000000000099"),
                            miniProfileId, "hydragon.abilities"
                    )
            ).join();
            assertEquals(BondedCompanionResultCode.NOT_FOUND, otherOwner.code());
        } finally {
            composition.close();
        }
    }

    /** Regression: adding optional family selection must not strand old replays. */
    @Test
    void noFamilyProvisionReplaysThePreFamilyRequestHash() throws Exception {
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, rosters, null, () -> 5_000L
                );
        BondedCompanionProvisionRequest request =
                new BondedCompanionProvisionRequest(
                        "test", "legacy-provision", OWNER,
                        "hydragon:dragons", "Tamed_Dragon_Fire",
                        "Ember", "Dragon", "Female",
                        Map.of("variant", "ember")
                );
        try {
            var created = composition.api().provision(request).join();
            assertEquals(BondedCompanionResultCode.SUCCESS, created.code());
            rewriteOperationHash(
                    temporaryDirectory.resolve(BondedCompanionDataPath.FILE_NAME),
                    request.callerNamespace(), request.idempotencyKey(),
                    legacyProvisionHash(request)
            );

            var replay = composition.api().provision(request).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, replay.code());
            assertEquals(created.value().profileId(), replay.value().profileId());
        } finally {
            composition.close();
        }
    }

    @Test
    void ambiguousProvisionRoleFailsClosedUntilFamilyIsExplicit()
            throws Exception {
        BondedCompanionRosterRegistry rosters = sharedRosterRegistry(true);
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory, rosters, null, () -> 5_000L
                );
        try {
            var ambiguous = composition.api().provision(
                    new BondedCompanionProvisionRequest(
                            "test", "ambiguous", OWNER, "hydragon:horn",
                            "Shared_Role", "Ambiguous", "Companion", null,
                            Map.of()
                    )
            ).join();
            var explicit = composition.api().provision(provision(
                    "explicit", "Shared_Role", "hydragon:miniwyvern"
            )).join();

            assertEquals(BondedCompanionResultCode.POLICY_DENIED,
                    ambiguous.code());
            assertEquals(BondedCompanionResultCode.SUCCESS, explicit.code());
            assertEquals("hydragon:miniwyvern", explicit.value().familyId());
        } finally {
            composition.close();
        }
    }

    private void insertTerminalOperation(
            Path database, String key, String state, long expiresAt)
            throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bonded_companion_operation(
                         caller_namespace, idempotency_key, owner_uuid,
                         roster_id, profile_id, operation_type, request_hash,
                         operation_state, result_json, created_at_ms,
                         updated_at_ms, expires_at_ms, expected_revision
                     ) VALUES ('maintenance-test', ?, ?, 'roster-a', NULL,
                         'REVIVE', ?, ?, ?, ?, ?, ?, NULL)
                     """)) {
            statement.setString(1, key);
            statement.setString(2, OWNER.toString());
            statement.setString(3, "e".repeat(64));
            statement.setString(4, state);
            statement.setString(5, """
                    {"code":"CONFLICT","reason":"maintenance-test",\
                    "valueType":"PROFILE","value":null}
                    """.replace("\\\n", ""));
            statement.setLong(6, expiresAt - 1L);
            statement.setLong(7, expiresAt - 1L);
            statement.setLong(8, expiresAt);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private long operationCount(Path database, String key) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM bonded_companion_operation
                     WHERE caller_namespace = 'maintenance-test'
                       AND idempotency_key = ?
                     """)) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }

    private void replaceDatabase(Path database) throws Exception {
        Files.move(database, database.resolveSibling("bonded-backup.sqlite"));
        Files.createFile(database);
    }

    private void rewriteOperationHash(
            Path database,
            String namespace,
            String key,
            String requestHash
    ) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bonded_companion_operation
                     SET request_hash = ?
                     WHERE caller_namespace = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, requestHash);
            statement.setString(2, namespace);
            statement.setString(3, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String legacyProvisionHash(BondedCompanionProvisionRequest request)
            throws Exception {
        StringBuilder payload = new StringBuilder();
        appendLegacyField(payload, request.ownerUuid().toString());
        appendLegacyField(payload, request.rosterId());
        appendLegacyField(payload, request.roleId());
        appendLegacyField(payload, request.displayName());
        appendLegacyField(payload, request.species());
        appendLegacyField(payload, request.gender());
        new java.util.TreeMap<>(request.snapshotPresentationData())
                .forEach((key, value) -> {
                    appendLegacyField(payload, key);
                    appendLegacyField(payload, value);
                });
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(payload.toString().getBytes(
                                java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    private void appendLegacyField(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
    }

    @Test
    void listenerFailureIsIsolatedAndUnknownWorldOutcomeIsNotPublished()
            throws Exception {
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null);
        AtomicInteger delivered = new AtomicInteger();
        publisher.subscribe(ignored -> {
            throw new IllegalStateException("listener-canary");
        });
        publisher.subscribe(ignored -> delivered.incrementAndGet());
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(
                "profile-canary", OWNER, "hydragon:dragons",
                BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE,
                4L, "summoned"
        );

        assertFalse(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.UNKNOWN
        ));
        assertEquals(0, delivered.get());
        assertTrue(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
        assertEquals(1, delivered.get());

        publisher.close();
        assertFalse(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.NOT_REQUIRED
        ));
        assertEquals(1, delivered.get());
    }

    @Test
    void closeCannotRaceAListenerIntoTheClosedPublisher() throws Exception {
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null, () -> {
                    registrationEntered.countDown();
                    try {
                        assertTrue(allowRegistration.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
        Thread subscriber = new Thread(() -> publisher.subscribe(ignored -> { }));

        subscriber.start();
        assertTrue(registrationEntered.await(5, TimeUnit.SECONDS));
        Thread closer = new Thread(publisher::close);
        closer.start();
        allowRegistration.countDown();
        subscriber.join(5_000L);
        closer.join(5_000L);

        assertEquals(0, publisher.listenerCount());
        assertFalse(subscriber.isAlive());
        assertFalse(closer.isAlive());
    }

    @Test
    void closeWaitsForConcurrentPublishAndStartsNoLaterListener()
            throws Exception {
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch allowListenerReturn = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger laterDeliveries = new AtomicInteger();
        publisher.subscribe(ignored -> {
            listenerEntered.countDown();
            try {
                assertTrue(allowListenerReturn.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        publisher.subscribe(ignored -> laterDeliveries.incrementAndGet());
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(
                "profile-close-race", OWNER, "hydragon:dragons",
                BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE,
                5L, "summoned"
        );

        Thread publishing = new Thread(() -> publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
        publishing.start();
        assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));
        Thread closing = new Thread(() -> {
            publisher.close();
            closeReturned.countDown();
        });
        closing.start();

        assertFalse(closeReturned.await(250L, TimeUnit.MILLISECONDS));
        allowListenerReturn.countDown();
        publishing.join(5_000L);
        closing.join(5_000L);

        assertFalse(publishing.isAlive());
        assertFalse(closing.isAlive());
        assertEquals(0, laterDeliveries.get());
    }

    @Test
    void listenerCanClosePublisherWithoutDeadlockOrLaterDelivery()
            throws Exception {
        BondedCompanionChangePublisher publisher =
                new BondedCompanionChangePublisher(null);
        CountDownLatch listenerClosed = new CountDownLatch(1);
        AtomicInteger laterDeliveries = new AtomicInteger();
        publisher.subscribe(ignored -> {
            publisher.close();
            listenerClosed.countDown();
        });
        publisher.subscribe(ignored -> laterDeliveries.incrementAndGet());
        BondedCompanionChangedEvent event = new BondedCompanionChangedEvent(
                "profile-reentrant-close", OWNER, "hydragon:dragons",
                BondedCompanionStateView.STORED,
                BondedCompanionStateView.ACTIVE,
                6L, "summoned"
        );

        Thread publishing = new Thread(() -> publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
        publishing.start();
        publishing.join(1_000L);

        assertFalse(publishing.isAlive());
        assertTrue(listenerClosed.await(1L, TimeUnit.SECONDS));
        assertEquals(0, laterDeliveries.get());
        assertFalse(publisher.publishCommitted(
                event,
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED
        ));
    }

    @Test
    void cleanupIdentityRequiresExactWorldUuidProfileKindAndLeaseToken() {
        UUID target = UUID.fromString(
                "20000000-0000-0000-0000-000000000005"
        );
        var intent = BondedCompanionProjectionCleanupService.CleanupIntent
                .projection(
                        "cleanup-1", OWNER, "hydragon:dragons", "profile-1",
                        "lease-1", target, "world-a", "store", -1L
                );
        TameworkProjectionIdentityComponent exact =
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-1", "lease-1"
                );

        assertTrue(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", target, exact
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-b", target, exact
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", UUID.randomUUID(), exact
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", target,
                TameworkProjectionIdentityComponent.bondedCompanion(
                        "profile-1", "replacement-lease"
                )
        ));
        assertFalse(HytaleBondedCompanionWorldGateway.matchesExactProjection(
                intent, "world-a", target,
                new TameworkProjectionIdentityComponent(
                        "profile-1", "lease-1",
                        TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                        null, null, 0L
                )
        ));
    }

    private BondedCompanionRosterRegistry rosterRegistry() throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:dragons",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["Tamed_Dragon_Fire"],
                                  "MaximumOwned": 4,
                                  "MaximumActive": 1,
                                  "Features": {"Provision": true}
                                }
                                """),
                        new ExtraInfo()
                );
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "HydragonDragons");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(config), 1L).applied());
        return registry;
    }

    private BondedCompanionProvisionRequest provision(
            String key,
            String roleId,
            String familyId
    ) {
        return new BondedCompanionProvisionRequest(
                "test", key, OWNER, "hydragon:horn", roleId,
                key, "Companion", null, Map.of(), familyId
        );
    }

    private BondedCompanionRosterRegistry sharedRosterRegistry(
            boolean sharedRole
    ) throws Exception {
        String dragonRole = sharedRole ? "Shared_Role" : "Tamed_Dragon_Fire";
        String miniRole = sharedRole ? "Shared_Role" : "Bonded_Miniwyvern";
        TwBondedCompanionRosterConfig dragons = policy(
                "Dragons", "hydragon:dragon", dragonRole
        );
        TwBondedCompanionRosterConfig minis = policy(
                "Miniwyverns", "hydragon:miniwyvern", miniRole
        );
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(dragons, minis), 2L).applied());
        return registry;
    }

    private TwBondedCompanionRosterConfig policy(
            String id,
            String familyId,
            String roleId
    ) throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:horn",
                                  "FamilyId": "%s",
                                  "AllowedRoles": ["%s"],
                                  "MaximumOwned": 1,
                                  "MaximumActive": 1,
                                  "Features": {"Provision": true}
                                }
                                """.formatted(familyId, roleId)),
                        new ExtraInfo()
                );
        Field configId = config.getClass().getDeclaredField("id");
        configId.setAccessible(true);
        configId.set(config, id);
        return config;
    }
}
