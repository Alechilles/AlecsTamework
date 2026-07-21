package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers namespace idempotency and dormant retention after optional projection failure. */
class CompanionProvisioningRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void retryReturnsOneCanonicalDormantProfileAfterProjectionFailure() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("provisioning.sqlite")) {
            CompanionProvisioningRepository repository = new CompanionProvisioningRepository(
                    harness.connections, harness.queue);
            CompanionProvisioningOperationRecord created = request("operation-a");
            assertEquals(CompanionProvisioningRepository.Status.CREATED,
                    await(repository.createAsync(created)).status());
            CompanionProvisioningRepository.MutationResult retry = await(
                    repository.createAsync(request("operation-b")));
            assertEquals(CompanionProvisioningRepository.Status.IDEMPOTENT, retry.status());
            assertEquals("operation-a", retry.operation().operationId());

            advance(repository, CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                    CompanionProvisioningOperationRecord.State.DORMANT_PREPARED,
                    null, "population-dormant", null, null, 2L);
            advance(repository, CompanionProvisioningOperationRecord.State.DORMANT_PREPARED,
                    CompanionProvisioningOperationRecord.State.DORMANT_APPLYING,
                    null, null, null, null, 3L);
            advance(repository, CompanionProvisioningOperationRecord.State.DORMANT_APPLYING,
                    CompanionProvisioningOperationRecord.State.DORMANT_COMMITTED,
                    "profile-canonical", null, null, "PROVISIONED_DORMANT", 4L);
            advance(repository, CompanionProvisioningOperationRecord.State.DORMANT_COMMITTED,
                    CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED,
                    "profile-canonical", null, "population-active", null, 5L);
            CompanionProvisioningRepository.MutationResult partial = advance(
                    repository, CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED,
                    CompanionProvisioningOperationRecord.State.PARTIAL_DORMANT,
                    "profile-canonical", null, "population-active",
                    "PROVISIONED_DORMANT", 6L);

            assertEquals(CompanionProvisioningRepository.Status.PARTIAL_DORMANT, partial.status());
            assertEquals("profile-canonical", partial.operation().canonicalProfileId());
            assertEquals("population-dormant", partial.operation().dormantPopulationOperationId());
            assertEquals("population-active", partial.operation().activePopulationOperationId());
            assertEquals("profile-canonical",
                    repository.findByCallerKey("hydragon", "soul-bond-1").canonicalProfileId());
            assertEquals("operation-a",
                    repository.findByCanonicalProfile("profile-canonical").operationId());
        }
    }

    @Test
    void aCallerKeyCannotBeReusedForDifferentOwner() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("provisioning-conflict.sqlite")) {
            CompanionProvisioningRepository repository = new CompanionProvisioningRepository(
                    harness.connections, harness.queue);
            await(repository.createAsync(request("operation-a")));
            CompanionProvisioningOperationRecord conflicting = new CompanionProvisioningOperationRecord(
                    "operation-c", "hydragon", "soul-bond-1", null,
                    UUID.randomUUID(), "miniwyvern", CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE,
                    "default", "{}", "{}", 8L, "profile-provisional",
                    null, CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                    null, null, null, null, "NONE", 1L, 1L, 0L);
            assertEquals(CompanionProvisioningRepository.Status.CONFLICT,
                    await(repository.createAsync(conflicting)).status());
        }
    }

    @Test
    void aCallerKeyCannotChangeOnlyInitialProfilePayload() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("provisioning-payload-conflict.sqlite")) {
            CompanionProvisioningRepository repository = new CompanionProvisioningRepository(
                    harness.connections, harness.queue);
            await(repository.createAsync(request("operation-a")));
            CompanionProvisioningOperationRecord changedName = new CompanionProvisioningOperationRecord(
                    "operation-b", "hydragon", "soul-bond-1", "quest-2",
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "miniwyvern", CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE,
                    "default", "{\"world\":\"default\"}", "{\"name\":\"Ember\"}",
                    8L, "profile-provisional", null,
                    CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                    null, null, null, null, "NONE", 1L, 1L, 0L);

            assertEquals(CompanionProvisioningRepository.Status.CONFLICT,
                    await(repository.createAsync(changedName)).status());
        }
    }

    private CompanionProvisioningRepository.MutationResult advance(
            CompanionProvisioningRepository repository,
            CompanionProvisioningOperationRecord.State expected,
            CompanionProvisioningOperationRecord.State next,
            String profileId, String dormantPopulationOperationId,
            String activePopulationOperationId, String resultCode,
            long nowMs) throws Exception {
        return await(repository.advanceAsync(new CompanionProvisioningRepository.AdvanceMutation(
                "operation-a", expected, next, profileId,
                dormantPopulationOperationId, activePopulationOperationId,
                resultCode, next == CompanionProvisioningOperationRecord.State.PARTIAL_DORMANT
                    ? "claim_capacity_denied" : null,
                null, nowMs)));
    }

    private CompanionProvisioningOperationRecord request(String operationId) {
        return new CompanionProvisioningOperationRecord(
                operationId, "hydragon", "soul-bond-1", "quest-1",
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "miniwyvern", CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE,
                "default", "{\"world\":\"default\"}", "{\"name\":\"Spark\"}",
                8L, "profile-provisional", null,
                CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                null, null, null, null, "NONE", 1L, 1L, 0L);
    }

    private HydragonPersistenceTestHarness harness(String filename) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(filename));
    }
}
