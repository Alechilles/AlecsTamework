package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers exactly-once capture resolution, idempotency, and durable cooldown authority. */
class CaptureAttemptRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void duplicateFailedRollReusesResultAndWritesOneNegativeWorldTimeCooldown() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("failure.sqlite")) {
            CaptureAttemptRepository repository = new CaptureAttemptRepository(
                    harness.connections, harness.queue);
            UUID actor = UUID.randomUUID();
            CaptureAttemptRecord prepared = probabilityAttempt(
                    "attempt-a", "capture-1", actor, UUID.randomUUID());

            assertEquals(CaptureAttemptRepository.PrepareStatus.PREPARED,
                    await(repository.prepareAsync(prepared)).status());
            CaptureAttemptRecord retryWithDifferentAttemptId = probabilityAttempt(
                    "attempt-b", "capture-1", actor, prepared.identity().targetNpcUuid());
            CaptureAttemptRepository.PrepareResult retry = await(
                    repository.prepareAsync(retryWithDifferentAttemptId));
            assertEquals(CaptureAttemptRepository.PrepareStatus.IDEMPOTENT, retry.status());
            assertEquals("attempt-a", retry.attempt().identity().attemptId());

            CaptureAttemptRepository.ResolutionMutation resolution = new CaptureAttemptRepository.ResolutionMutation(
                    "attempt-a", false,
                    new CaptureAttemptRecord.Resolution(
                            1.0, 2.0, 4.0, 10.0, 0.6, 0.2, 0.45,
                            0.91, null, "failed_roll", -500L, -1_000L),
                    "population-a", null);
            assertEquals(CaptureAttemptRepository.MutationStatus.APPLIED,
                    await(repository.resolveAsync(resolution)).status());
            assertEquals(CaptureAttemptRepository.MutationStatus.IDEMPOTENT,
                    await(repository.resolveAsync(resolution)).status());
            assertEquals(CaptureAttemptRepository.PrepareStatus.IDEMPOTENT,
                    await(repository.prepareAsync(prepared)).status());

            CaptureAttemptRepository.FailureCooldown cooldown = repository.findFailureCooldown(
                    actor, "stone-config");
            assertEquals(-500L, cooldown.cooldownUntilMs());
            assertEquals(1L, cooldown.generation());
            assertEquals("attempt-a", cooldown.attemptId());
            assertTrue(await(repository.markEventEmittedAsync("attempt-a", -400L)));
            assertFalse(await(repository.markEventEmittedAsync("attempt-a", -300L)));
            assertEquals(-400L, repository.find("attempt-a").eventEmittedAtMs());

            CaptureAttemptRepository.DiagnosticsSummary summary =
                    repository.summarizeDiagnostics();
            assertEquals(0L, summary.prepared());
            assertEquals(1L, summary.resolvedFailure());
            assertEquals(0L, summary.applying());
            assertEquals(0L, summary.quarantined());
            assertEquals(0L, summary.recovered());
            assertEquals(1L, summary.duplicateCallbacksSinceBoot());
        }
    }

    @Test
    void guaranteedAttemptRecordsNoEntropyAndProbabilityAttemptRequiresIt() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("guaranteed.sqlite")) {
            CaptureAttemptRepository repository = new CaptureAttemptRepository(
                    harness.connections, harness.queue);
            CaptureAttemptRecord guaranteed = guaranteedAttempt();
            await(repository.prepareAsync(guaranteed));
            CaptureAttemptRepository.MutationResult resolved = await(repository.resolveAsync(
                    new CaptureAttemptRepository.ResolutionMutation(
                            guaranteed.identity().attemptId(), true,
                            new CaptureAttemptRecord.Resolution(
                                    0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0,
                                    null, null, "guaranteed", 0L, 10L),
                            "population-guaranteed", "capture-guaranteed")));
            assertEquals(CaptureAttemptRecord.State.RESOLVED_SUCCESS, resolved.attempt().state());
            assertNull(resolved.attempt().resolution().entropySample());

            CaptureAttemptRecord probability = probabilityAttempt(
                    "attempt-p", "capture-p", UUID.randomUUID(), UUID.randomUUID());
            await(repository.prepareAsync(probability));
            PersistenceWriteQueue.WriteOutcome<CaptureAttemptRepository.MutationResult> outcome =
                    repository.resolveAsync(
                    new CaptureAttemptRepository.ResolutionMutation(
                            "attempt-p", true,
                            new CaptureAttemptRecord.Resolution(
                                    1, 1, 1, 2, 0.5, 0, 0.5,
                                    null, null, "missing_entropy", 0, 20),
                            null, null))
                    .completion().get(5, TimeUnit.SECONDS);
            assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, outcome.status());
            assertTrue(outcome.failure() instanceof IllegalArgumentException);

            CaptureAttemptRecord certainProbability = probabilityAttempt(
                    "attempt-certain", "capture-certain", UUID.randomUUID(), UUID.randomUUID());
            await(repository.prepareAsync(certainProbability));
            CaptureAttemptRepository.MutationResult certain = await(repository.resolveAsync(
                    new CaptureAttemptRepository.ResolutionMutation(
                            "attempt-certain", true,
                            new CaptureAttemptRecord.Resolution(
                                    5, 1, 1, 2, 0.5, 0, 1.0,
                                    null, "CAPTURED", "guaranteed_at_power", 0, 30),
                            null, "capture-certain-operation")));
            assertEquals(CaptureAttemptRecord.State.RESOLVED_SUCCESS, certain.attempt().state());
            assertNull(certain.attempt().resolution().entropySample());
        }
    }

    @Test
    void terminalCompactionPersistsTombstoneAndRejectsLateAttemptAndCallerRetries()
            throws Exception {
        Path database = tempDir.resolve("tombstone.sqlite");
        CaptureAttemptRecord terminal;
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(database)) {
            CaptureAttemptRepository repository = new CaptureAttemptRepository(
                    harness.connections, harness.queue);
            terminal = probabilityAttempt(
                    "attempt-terminal", "stable-caller-key",
                    UUID.randomUUID(), UUID.randomUUID());
            await(repository.prepareAsync(terminal));
            assertEquals(CaptureAttemptRepository.MutationStatus.APPLIED,
                    await(repository.advanceAsync(
                            terminal.identity().attemptId(), CaptureAttemptRecord.State.PREPARED,
                            CaptureAttemptRecord.State.CANCELED, "precondition-denied", null, 100L)).status());

            CaptureAttemptRepository.CompactionResult compacted = await(
                    repository.compactTerminalAsync(100L, 200L, 10_000L, 8));
            assertEquals(1, compacted.compactedAttempts());
            assertNull(repository.find(terminal.identity().attemptId()));
            assertEquals(CaptureAttemptRepository.PrepareStatus.TOMBSTONED,
                    await(repository.prepareAsync(terminal)).status());

            CaptureAttemptRecord sameCaller = probabilityAttempt(
                    "attempt-late", "stable-caller-key",
                    terminal.identity().actorUuid(), terminal.identity().targetNpcUuid());
            assertEquals(CaptureAttemptRepository.PrepareStatus.TOMBSTONED,
                    await(repository.prepareAsync(sameCaller)).status());
        }

        try (HydragonPersistenceTestHarness restarted = new HydragonPersistenceTestHarness(database)) {
            CaptureAttemptRepository repository = new CaptureAttemptRepository(
                    restarted.connections, restarted.queue);
            assertEquals(CaptureAttemptRepository.PrepareStatus.TOMBSTONED,
                    await(repository.prepareAsync(terminal)).status());
            assertEquals(CaptureAttemptRepository.MutationStatus.TOMBSTONED,
                    await(repository.advanceAsync(
                            terminal.identity().attemptId(), CaptureAttemptRecord.State.PREPARED,
                            CaptureAttemptRecord.State.CANCELED,
                            "late-duplicate", null, 300L)).status());
        }
    }

    private CaptureAttemptRecord probabilityAttempt(String attemptId, String key,
                                                    UUID actor, UUID target) {
        return new CaptureAttemptRecord(
                new CaptureAttemptRecord.Identity(
                        attemptId, "hydragon", key, actor, target, null, null,
                        "empty-stone", "wild-dragon", "{\"slot\":2}"),
                new CaptureAttemptRecord.ConfigEvidence(
                        "stone-config", 7L, "dragon-policy", 3L, false, false),
                CaptureAttemptRecord.State.PREPARED, null, null, null,
                0L, "NONE", 5_000L, 1L, 1L, 0L, null);
    }

    private CaptureAttemptRecord guaranteedAttempt() {
        return new CaptureAttemptRecord(
                new CaptureAttemptRecord.Identity(
                        "attempt-g", null, null, UUID.randomUUID(), UUID.randomUUID(),
                        null, null, "legacy-stone", null, "{}"),
                new CaptureAttemptRecord.ConfigEvidence(
                        "legacy-config", 1L, null, null, true, true),
                CaptureAttemptRecord.State.PREPARED, null, null, null,
                0L, "NONE", 5_000L, 1L, 1L, 0L, null);
    }

    private HydragonPersistenceTestHarness harness(String filename) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(filename));
    }
}
