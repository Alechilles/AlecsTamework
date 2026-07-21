package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiProfileDataRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void supportsProfileScopedCrudAndPreservesTimestamps() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    ownerUuid,
                    "Owner A",
                    "Mob_Test",
                    "Display A",
                    "Custom A",
                    true,
                    null,
                    null,
                    null,
                    new String[] {"tool-a"}
            )));

            assertTrue(awaitUntil(() -> runtime.getNpcProfileRepository().resolveProfileId(npcUuid) != null));
            String profileId = runtime.getNpcProfileRepository().resolveProfileId(npcUuid);
            assertNotNull(profileId);

            ApiProfileDataRepository repository = runtime.getApiProfileDataRepository();
            assertTrue(repository.putAsync(profileId, "example.plugin", "alpha", "{\"value\":1}"));
            assertTrue(awaitUntil(() -> "{\"value\":1}".equals(repository.get(profileId, "example.plugin", "alpha"))));

            Thread.sleep(25L);
            assertTrue(repository.putAsync(profileId, "example.plugin", "alpha", "{\"value\":2}"));
            assertTrue(awaitUntil(() -> "{\"value\":2}".equals(repository.get(profileId, "example.plugin", "alpha"))));

            Map<String, String> values = repository.list(profileId, "example.plugin");
            assertEquals(Map.of("alpha", "{\"value\":2}"), values);

            TimestampRow timestamps = loadTimestamps(profileId, "example.plugin", "alpha");
            assertTrue(timestamps.createdAtMs() > 0L);
            assertTrue(timestamps.updatedAtMs() >= timestamps.createdAtMs());

            assertTrue(repository.deleteAsync(profileId, "example.plugin", "alpha"));
            assertTrue(awaitUntil(() -> repository.get(profileId, "example.plugin", "alpha") == null));
            assertTrue(repository.list(profileId, "example.plugin").isEmpty());
        }
    }

    @Test
    void rejectsReservedInvalidOrUnknownProfileScopes() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ApiProfileDataRepository repository = runtime.getApiProfileDataRepository();
            assertFalse(repository.putAsync("missing", "example.plugin", "alpha", "{\"value\":1}"));
            assertFalse(repository.putAsync("missing", "Alechilles:Tamework", "alpha", "{\"value\":1}"));
            assertFalse(repository.putAsync("missing", "example.plugin", " ", "{\"value\":1}"));
            assertFalse(repository.deleteAsync("missing", "example.plugin", "alpha"));
            assertTrue(repository.list("missing", "example.plugin").isEmpty());
        }
    }

    @Test
    void compareAndSetIsRevisionFencedIdempotentAndRestartVisible() throws Exception {
        String profileId;
        UUID npcUuid = UUID.randomUUID();
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, UUID.randomUUID(), "Owner", "Mob_Test", "Display", null,
                    true, null, null, null, new String[0])));
            assertTrue(awaitUntil(() -> runtime.getNpcProfileRepository().resolveProfileId(npcUuid) != null));
            profileId = runtime.getNpcProfileRepository().resolveProfileId(npcUuid);

            ApiProfileDataRepository repository = runtime.getApiProfileDataRepository();
            ApiProfileDataRepository.TransactionResult created = repository.compareAndSetAsync(
                            profileId, "Alechilles:HyDragon", "state", 0L,
                            "attune:1", " { \"archetype\" : \"fire\" } ")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ApiProfileDataRepository.TransactionOutcome.COMMITTED, created.outcome());
            assertEquals(1L, created.value().revision());
            assertEquals("{\"archetype\":\"fire\"}", created.value().jsonPayload());
            assertEquals(64, created.operation().payloadFingerprint().length());

            ApiProfileDataRepository.TransactionResult replay = repository.compareAndSetAsync(
                            profileId, "Alechilles:HyDragon", "state", 0L,
                            "attune:1", "{\"archetype\":\"fire\"}")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(created.operation().operationId(), replay.operation().operationId());
            assertEquals(1L, replay.value().revision());

            ApiProfileDataRepository.TransactionResult denied = repository.compareAndSetAsync(
                            profileId, "Alechilles:HyDragon", "state", 0L,
                            "attune:stale", "{\"archetype\":\"ice\"}")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ApiProfileDataRepository.TransactionOutcome.TERMINAL_DENIED, denied.outcome());
            assertEquals("revision-mismatch", denied.reason());
            assertNull(denied.value());
        }

        try (TameworkPersistenceRuntime restarted = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ApiProfileDataRepository repository = restarted.getApiProfileDataRepository();
            ApiProfileDataRepository.TransactionOperation committed =
                    repository.findOperation("Alechilles:HyDragon", "attune:1");
            ApiProfileDataRepository.TransactionOperation denied =
                    repository.findOperation("Alechilles:HyDragon", "attune:stale");
            assertNotNull(committed);
            assertNotNull(denied);
            assertEquals(ApiProfileDataRepository.TransactionStatus.COMMITTED, committed.status());
            assertEquals(ApiProfileDataRepository.TransactionStatus.TERMINAL_DENIED, denied.status());
            assertEquals(1L, repository.getVersioned(
                    profileId, "Alechilles:HyDragon", "state").revision());
        }
    }

    @Test
    void conflictingIdempotencyReplayIsDurablyQuarantined() throws Exception {
        String profileId;
        UUID npcUuid = UUID.randomUUID();
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, UUID.randomUUID(), "Owner", "Mob_Test", "Display", null,
                    true, null, null, null, new String[0])));
            assertTrue(awaitUntil(() -> runtime.getNpcProfileRepository().resolveProfileId(npcUuid) != null));
            profileId = runtime.getNpcProfileRepository().resolveProfileId(npcUuid);
            ApiProfileDataRepository repository = runtime.getApiProfileDataRepository();
            assertEquals(ApiProfileDataRepository.TransactionOutcome.COMMITTED,
                    repository.compareAndSetAsync(profileId, "Alechilles:HyDragon", "state", 0L,
                                    "same-key", "{\"value\":1}")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());

            ApiProfileDataRepository.TransactionResult conflict = repository.compareAndSetAsync(
                            profileId, "Alechilles:HyDragon", "state", 0L,
                            "same-key", "{\"value\":2}")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ApiProfileDataRepository.TransactionOutcome.QUARANTINED, conflict.outcome());
            assertEquals("idempotency-key-conflict", conflict.reason());
        }

        try (TameworkPersistenceRuntime restarted = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertEquals(ApiProfileDataRepository.TransactionStatus.QUARANTINED,
                    restarted.getApiProfileDataRepository()
                            .findOperation("Alechilles:HyDragon", "same-key").status());
        }
    }

    private TimestampRow loadTimestamps(String profileId, String namespace, String key) throws Exception {
        Path sqlitePath = tempDir.resolve(TameworkPersistenceRuntime.SQLITE_FILENAME);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT created_at_ms, updated_at_ms
                     FROM api_profile_data
                     WHERE profile_id = ? AND namespace = ? AND data_key = ?
                     """
             )) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            statement.setString(3, key);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return new TimestampRow(rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));
            }
        }
    }

    private boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    private record TimestampRow(long createdAtMs, long updatedAtMs) {
    }
}
