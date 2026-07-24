package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
    void genericRefundClaimConvergesConsumedSuccessfulEvidence() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("capture-refund.sqlite")) {
            CaptureAttemptRepository repository = new CaptureAttemptRepository(
                    harness.connections, harness.queue);
            UUID actor = UUID.randomUUID();
            CaptureAttemptRecord attempt = capturedItemAttempt("attempt-charged", actor);
            await(repository.prepareAsync(attempt));
            await(repository.resolveAsync(successResolution(attempt.identity().attemptId())));

            markSuccessfulSourceConsumed(harness, attempt.identity().attemptId());

            assertTrue(await(repository.requireSourceRefundAsync(
                    attempt.identity().attemptId(), "successful-apply-lost", 220L)));
            assertEquals(1, repository.loadPendingSourceRefunds(actor).size());
            assertTrue(await(repository.completeSourceRefundAsync(
                    attempt.identity().attemptId(), 230L)));
            assertEquals(CaptureAttemptRecord.State.CANCELED,
                    repository.find(attempt.identity().attemptId()).state());
            assertTrue(repository.loadPendingSourceRefunds(actor).stream()
                    .allMatch(CaptureAttemptRepository.SourceRefundClaim::delivered));
        }
    }

    @Test
    void resolvedAttemptFailurePublishesNothingUntilExactSourceSpendIsConfirmed() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("resolved-spend.sqlite")) {
            CaptureAttemptRepository repository = new CaptureAttemptRepository(
                    harness.connections, harness.queue);
            UUID actor = UUID.randomUUID();
            CaptureAttemptRecord prepared = new CaptureAttemptRecord(
                    new CaptureAttemptRecord.Identity(
                            "attempt-spend", "hydragon", "capture-spend", actor,
                            UUID.randomUUID(), null, null, "draconic-stone", "wild-dragon",
                            "{\"slot\":2}"),
                    new CaptureAttemptRecord.ConfigEvidence(
                            "stone-config", 8L, "dragon-policy", 3L, false, false,
                            CaptureSourceConsumption.RESOLVED_ATTEMPT,
                            CaptureSuccessDisposition.CAPTURED_ITEM),
                    CaptureAttemptRecord.State.PREPARED, null, null, null,
                    0L, "NONE", 5_000L, 1L, 1L, 0L, null);
            await(repository.prepareAsync(prepared));

            CaptureAttemptRepository.MutationResult resolved = await(repository.resolveAsync(
                    new CaptureAttemptRepository.ResolutionMutation(
                            "attempt-spend", false,
                            new CaptureAttemptRecord.Resolution(
                                    1, 2, 4, 10, 0.6, 0.2, 0.45,
                                    0.91, null, "failed_roll", 7_000L, 6_000L),
                            "population-spend", null, true,
                            "before-fingerprint", "after-fingerprint")));

            assertEquals(CaptureAttemptRecord.SourceSpendState.PENDING,
                    resolved.attempt().sourceSpend().state());
            assertNull(repository.findFailureCooldown(actor, "stone-config"));
            assertFalse(await(repository.markEventEmittedAsync("attempt-spend", 6_100L)));
            assertEquals(CaptureAttemptRepository.MutationStatus.INVALID_STATE,
                    await(repository.markSourceConsumedAsync(
                            "attempt-spend", 6_150L)).status());
            assertEquals(CaptureAttemptRepository.MutationStatus.APPLIED,
                    await(repository.markSourceReceiptedAsync(
                            "attempt-spend", 6_175L)).status());

            CaptureAttemptRepository.MutationResult consumed = await(
                    repository.markSourceConsumedAsync("attempt-spend", 6_200L));
            assertEquals(CaptureAttemptRecord.SourceSpendState.CONSUMED,
                    consumed.attempt().sourceSpend().state());
            assertEquals(7_000L, repository.findFailureCooldown(
                    actor, "stone-config").cooldownUntilMs());
            assertTrue(await(repository.markEventEmittedAsync("attempt-spend", 6_300L)));
            assertEquals(CaptureAttemptRepository.MutationStatus.IDEMPOTENT,
                    await(repository.markSourceConsumedAsync("attempt-spend", 6_400L)).status());
        }
    }

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

    private CaptureAttemptRecord capturedItemAttempt(String attemptId, UUID actor) {
        return new CaptureAttemptRecord(
                new CaptureAttemptRecord.Identity(
                        attemptId, "hydragon", attemptId, actor, UUID.randomUUID(),
                        null, null, "draconic-stone", "wild-dragon",
                        "{\"version\":1,\"world\":\"default\",\"inventory\":\"hotbar\","
                                + "\"slot\":2,\"fingerprint\":\"before\"}"),
                new CaptureAttemptRecord.ConfigEvidence(
                        "stone-config", 9L, null, null, true, true),
                CaptureAttemptRecord.State.PREPARED, null, "population-" + attemptId,
                null, 0L, "NONE", 5_000L, 1L, 1L, 0L, null);
    }

    private CaptureAttemptRepository.ResolutionMutation successResolution(String attemptId) {
        return new CaptureAttemptRepository.ResolutionMutation(
                attemptId, true,
                new CaptureAttemptRecord.Resolution(
                        5, 1, 1, 10, 0.9, 0.1, 1.0,
                        null, "CAPTURED", "captured", 0L, 50L),
                "population-" + attemptId, "capture-" + attemptId,
                false, null, null);
    }

    private void markSuccessfulSourceConsumed(
            HydragonPersistenceTestHarness harness, String attemptId) throws Exception {
        // Retain compensation coverage independently from the deleted tame-link producer.
        try (Connection connection = harness.connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE capture_attempts
                     SET source_spend_state = 'CONSUMED',
                         source_spend_before_fingerprint = 'before',
                         source_spend_after_fingerprint = 'after',
                         source_spend_receipted_at_ms = 200,
                         source_spend_at_ms = 210
                     WHERE attempt_id = ?
                     """)) {
            statement.setString(1, attemptId);
            assertEquals(1, statement.executeUpdate());
        }
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
