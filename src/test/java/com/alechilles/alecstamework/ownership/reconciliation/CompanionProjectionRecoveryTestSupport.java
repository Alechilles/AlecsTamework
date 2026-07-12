package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.Harness;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationReleaseCommitRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopPopulationMutationContext;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.HousedResidentClaim;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.insertScenario;
import static com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationOperationRecoveryTestSupport.updateTargetContext;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable projection-marker fixtures shared by operation-recovery regression tests. */
final class CompanionProjectionRecoveryTestSupport {
    private CompanionProjectionRecoveryTestSupport() {
    }

    static void prepareManagedCoopRelease(
            Harness harness,
            UUID previousNpcUuid,
            UUID plannedNpcUuid,
            UUID ownerUuid,
            ManagedCoopAuthorityKey authority
    ) throws Exception {
        insertScenario(
                harness, "profile", previousNpcUuid, ownerUuid, ownerUuid,
                CompanionLifecycleState.COOP, CompanionLifecycleState.ACTIVE,
                "default", "default", OwnerPopulationOperation.RESTORE,
                CompanionPopulationOperationRecord.State.APPLYING, false
        );
        String snapshot = "{\"version\":1,\"npcUuid\":\"" + previousNpcUuid + "\"}";
        String snapshotHash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot);
        assertTrue(await(harness.residents().registerAuthority(
                authority, "coop_chicken", AuthorityState.TWORK_MANAGED, 1L)).succeeded());
        assertTrue(await(harness.residents().claimHoused(new HousedResidentClaim(
                "resident-profile", authority, "coop_chicken", 2, "profile", "mob_chicken",
                previousNpcUuid, snapshot, snapshotHash, 1, 2L
        ))).succeeded());
        var resident = harness.residents().loadById("resident-profile");
        ReleaseRequest release = new ReleaseRequest(
                "coop-release-operation", resident.residentId(), authority, "coop_chicken", 2,
                "profile", plannedNpcUuid, resident.snapshotHash(), resident.generation(), 3L
        );
        var prepared = await(harness.lifecycle().prepareRelease(release));
        assertTrue(prepared.succeeded());
        var claimed = await(harness.lifecycle().claimReleaseSpawn(
                release.operationId(), prepared.operation().generation(), 4L));
        assertTrue(claimed.succeeded());
        PopulationReleaseCommitRequest commit = new PopulationReleaseCommitRequest(
                release.operationId(), release.residentId(), release.authorityKey(),
                release.coopId(), release.residentSlot(), release.profileId(), plannedNpcUuid,
                plannedNpcUuid, release.snapshotHash(), release.expectedResidentGeneration(),
                claimed.operation().generation(), 5L
        );
        updateTargetContext(harness, managedCoopTarget(
                previousNpcUuid, plannedNpcUuid,
                ManagedCoopPopulationMutationContext.releaseExtensionJson(commit)));
    }

    static CompanionPopulationOperationRecord operationRecord(
            OwnerPopulationOperation operation,
            CompanionPopulationOperationRecord.State state,
            UUID oldOwner,
            CompanionLifecycleState oldLifecycle,
            UUID newOwner,
            CompanionLifecycleState newLifecycle,
            String targetContext
    ) {
        return new CompanionPopulationOperationRecord(
                "operation", "profile", operation.name(), state, 0L,
                ownerState(oldOwner, oldLifecycle), ownerState(newOwner, newLifecycle),
                targetContext, 1L, 1L, 0L, null
        );
    }

    static CompanionPopulationEvidence breedingMarker(
            UUID plannedNpcUuid,
            UUID componentUuid,
            UUID legacyNpcUuid,
            UUID ownerUuid,
            boolean ownerObserved,
            String world,
            int chunkX,
            int chunkZ,
            String key
    ) {
        return breedingMarker(
                plannedNpcUuid, componentUuid, legacyNpcUuid, ownerUuid, ownerObserved,
                world, chunkX, chunkZ, key, false
        );
    }

    static CompanionPopulationEvidence breedingMarker(
            UUID plannedNpcUuid,
            UUID componentUuid,
            UUID legacyNpcUuid,
            UUID ownerUuid,
            boolean ownerObserved,
            String world,
            int chunkX,
            int chunkZ,
            String key,
            boolean deathObserved
    ) {
        return projectionMarker(
                "attempt", TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                "child-0000", plannedNpcUuid, 1L, plannedNpcUuid,
                componentUuid, legacyNpcUuid, ownerUuid, ownerObserved,
                world, chunkX, chunkZ, key, deathObserved
        );
    }

    static CompanionPopulationEvidence coopReleaseMarker(
            UUID previousNpcUuid,
            UUID componentUuid,
            UUID legacyNpcUuid,
            UUID ownerUuid,
            ManagedCoopAuthorityKey authority,
            String key
    ) {
        return projectionMarker(
                "coop-release-operation",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                authority.slotKey(2), previousNpcUuid, 1L, componentUuid,
                componentUuid, legacyNpcUuid, ownerUuid, true,
                "default", 0, 0, key, false
        );
    }

    private static String managedCoopTarget(
            UUID previousNpcUuid,
            UUID plannedNpcUuid,
            String extensionJson
    ) {
        JsonObject target = new JsonObject();
        target.addProperty("operation", "coop_release");
        target.addProperty("previousNpcUuid", previousNpcUuid.toString());
        target.addProperty("plannedNpcUuid", plannedNpcUuid.toString());
        target.addProperty("world", "default");
        target.addProperty("chunkX", 0);
        target.addProperty("chunkZ", 0);
        for (var field : JsonParser.parseString(extensionJson).getAsJsonObject().entrySet()) {
            target.add(field.getKey(), field.getValue().deepCopy());
        }
        return target.toString();
    }

    private static <T> T await(PersistenceWriteQueue.WriteSubmission<T> submission)
            throws Exception {
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(
                3, TimeUnit.SECONDS);
        assertTrue(outcome.isCommitted(), outcome.failureReason());
        return outcome.value();
    }

    private static String ownerState(UUID owner, CompanionLifecycleState lifecycle) {
        String ownerJson = owner == null ? "null" : "\"" + owner + "\"";
        return "{\"ownerUuid\":" + ownerJson + ",\"lifecycleState\":\""
                + lifecycle.name() + "\",\"ownershipWorldName\":\"default\"}";
    }

    private static CompanionPopulationEvidence projectionMarker(
            String operationId,
            String projectionKind,
            String slotKey,
            UUID sourceNpcUuid,
            long generation,
            UUID evidenceNpcUuid,
            UUID componentUuid,
            UUID legacyNpcUuid,
            UUID ownerUuid,
            boolean ownerObserved,
            String world,
            int chunkX,
            int chunkZ,
            String key,
            boolean deathObserved
    ) {
        String fingerprint = CompanionProjectionEvidence.fingerprint(
                "profile", operationId, projectionKind, slotKey, sourceNpcUuid, generation);
        return new CompanionPopulationEvidence(
                CompanionProjectionEvidence.appendToEvidenceKey(
                        "projection-" + key, fingerprint, componentUuid, legacyNpcUuid,
                        deathObserved),
                evidenceNpcUuid,
                ownerUuid,
                ownerObserved,
                CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                world,
                world,
                chunkX,
                chunkZ,
                "test"
        );
    }
}
