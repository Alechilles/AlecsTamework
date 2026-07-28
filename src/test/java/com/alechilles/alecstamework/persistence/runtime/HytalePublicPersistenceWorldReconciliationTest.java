package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for sealed public startup-world reconciliation. */
class HytalePublicPersistenceWorldReconciliationTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias IMPORTED_ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias COMPONENT_ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000002"
    );
    private static final LoadedNpcIdentityIndex.Location WORLD =
            new LoadedNpcIdentityIndex.Location("world-a", "store-a");

    @Test
    void positiveSealedObservationSubmitsOneCanonicalReconciliation() {
        LoadedNpcIdentityIndex index = sealedIndex();
        index.recordAdded(observation(COMPONENT_ALIAS, IMPORTED_ALIAS));
        FakeAccess access = access(List.of(unresolved()), profile());
        HytalePublicPersistenceWorldReconciliation reconciliation =
                reconciliation(index, access);

        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );
        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.reconcile().toCompletableFuture().join()
        );

        assertEquals(1, access.submitted.size());
        CompanionProfileMutation.ReconcileLoaded submitted =
                (CompanionProfileMutation.ReconcileLoaded)
                        access.submitted.getFirst();
        assertEquals(PROFILE, submitted.profileId());
        assertEquals(IMPORTED_ALIAS, submitted.expectedCurrentAlias());
        assertEquals(COMPONENT_ALIAS, submitted.observedAlias());
        assertEquals("world-a", submitted.worldKey());
        assertTrue(access.operationIds.getFirst().toString()
                .matches("[0-9a-f-]{36}"));
        assertTrue(access.idempotencyKeys.getFirst().toString()
                .startsWith("world-reconcile-v1:"));
    }

    @Test
    void sealedAbsenceSubmitsOneCanonicalUnloadedReconciliation() {
        LoadedNpcIdentityIndex index = sealedIndex();
        FakeAccess access = access(List.of(unresolved()), profile());
        HytalePublicPersistenceWorldReconciliation reconciliation =
                reconciliation(index, access);

        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );
        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.reconcile().toCompletableFuture().join()
        );
        assertEquals(1, access.submitted.size());
        CompanionProfileMutation.ReconcileUnloaded submitted =
                (CompanionProfileMutation.ReconcileUnloaded)
                        access.submitted.getFirst();
        assertEquals(PROFILE, submitted.profileId());
        assertEquals(IMPORTED_ALIAS, submitted.expectedCurrentAlias());
    }

    @Test
    void unsealedZeroWorldStartupDefersUntilAWorldSuppliesPositiveEvidence() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        FakeAccess access = access(List.of(unresolved()), profile());
        CompletableFuture<com.alechilles.alecstamework.items
                .LoadedNpcIdentitySnapshot> evidence = new CompletableFuture<>();
        HytalePublicPersistenceWorldReconciliation reconciliation =
                new HytalePublicPersistenceWorldReconciliation(
                        index,
                        () -> evidence,
                        access,
                        () -> -8_000
                );

        assertEquals(
                PublicPersistenceWorldReconciliation.Result.DEFERRED,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );

        index.recordAdded(observation(COMPONENT_ALIAS, IMPORTED_ALIAS));
        index.markInitializationComplete();
        evidence.complete(index.snapshot());
        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );
        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.reconcile().toCompletableFuture().join()
        );
        assertEquals(1, access.submitted.size());
    }

    @Test
    void freshWorldWithNoCanonicalProfilesNeedsNoWorldScan() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        FakeAccess access = access(List.of());
        CompletableFuture<com.alechilles.alecstamework.items
                .LoadedNpcIdentitySnapshot> never = new CompletableFuture<>();
        HytalePublicPersistenceWorldReconciliation reconciliation =
                new HytalePublicPersistenceWorldReconciliation(
                        index,
                        () -> never,
                        access,
                        () -> -8_000
                );

        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );
        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.reconcile().toCompletableFuture().join()
        );
        assertTrue(access.submitted.isEmpty());
        assertFalse(never.isDone());
    }

    @Test
    void unresolvedImportWithoutSealedPositiveEvidenceDefersWithoutBlocking() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        FakeAccess access = access(List.of(unresolved()));
        CompletableFuture<com.alechilles.alecstamework.items
                .LoadedNpcIdentitySnapshot> pending = new CompletableFuture<>();
        HytalePublicPersistenceWorldReconciliation reconciliation =
                new HytalePublicPersistenceWorldReconciliation(
                        index,
                        () -> pending,
                        access,
                        () -> -8_000
                );

        assertEquals(
                PublicPersistenceWorldReconciliation.Result.DEFERRED,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );
        assertFalse(pending.isDone());
        assertTrue(access.submitted.isEmpty());
    }

    @Test
    void duplicateLiveEvidenceFailsClosed() {
        LoadedNpcIdentityIndex index = sealedIndex();
        index.recordAdded(observation(COMPONENT_ALIAS, IMPORTED_ALIAS));
        index.recordAdded(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                IMPORTED_ALIAS.value(),
                new LoadedNpcIdentityIndex.Location("world-b", "store-b"),
                null
        ));
        FakeAccess access = access(List.of(unresolved()), profile());
        HytalePublicPersistenceWorldReconciliation reconciliation =
                reconciliation(index, access);

        assertThrows(
                RuntimeException.class,
                () -> reconciliation.awaitEvidence()
                        .toCompletableFuture().join()
        );
        assertTrue(access.submitted.isEmpty());
    }

    @Test
    void lifecycleReadAbsenceIsNotSuccessfulAbsence() {
        LoadedNpcIdentityIndex index = sealedIndex();
        FakeAccess access = access(List.of());
        access.read = PersistenceReadResult.absent();
        HytalePublicPersistenceWorldReconciliation reconciliation =
                reconciliation(index, access);

        assertThrows(
                RuntimeException.class,
                () -> reconciliation.awaitEvidence()
                        .toCompletableFuture().join()
        );
    }

    @Test
    void quiesceDropsEvidenceAndPreventsNewSubmissions() {
        LoadedNpcIdentityIndex index = sealedIndex();
        index.recordAdded(observation(COMPONENT_ALIAS, IMPORTED_ALIAS));
        FakeAccess access = access(List.of(unresolved()), profile());
        HytalePublicPersistenceWorldReconciliation reconciliation =
                reconciliation(index, access);
        assertEquals(
                PublicPersistenceWorldReconciliation.Result.COMPLETE,
                reconciliation.awaitEvidence().toCompletableFuture().join()
        );

        reconciliation.quiesce();

        assertEquals(
                PublicPersistenceWorldReconciliation.Result.DEFERRED,
                reconciliation.reconcile().toCompletableFuture().join()
        );
        assertThrows(
                RuntimeException.class,
                () -> reconciliation.awaitEvidence()
                        .toCompletableFuture().join()
        );
        assertTrue(access.submitted.isEmpty());
    }

    private HytalePublicPersistenceWorldReconciliation reconciliation(
            LoadedNpcIdentityIndex index,
            FakeAccess access
    ) {
        return new HytalePublicPersistenceWorldReconciliation(
                index,
                () -> CompletableFuture.completedFuture(index.snapshot()),
                access,
                () -> -8_000
        );
    }

    private LoadedNpcIdentityIndex sealedIndex() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.markInitializationComplete();
        return index;
    }

    private LoadedNpcIdentityIndex.LoadedNpcObservation observation(
            NpcAlias componentAlias,
            NpcAlias legacyAlias
    ) {
        return new LoadedNpcIdentityIndex.LoadedNpcObservation(
                componentAlias.value(),
                legacyAlias.value(),
                WORLD,
                null
        );
    }

    private CompanionLifecycle unresolved() {
        return new CompanionLifecycle(
                PROFILE,
                OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                LifecycleState.UNRESOLVED,
                LifecycleLocation.unresolved(),
                LifecycleRevision.INITIAL,
                null,
                -9_000,
                ReconciliationGeneration.INITIAL,
                null,
                "owner-world"
        );
    }

    private CompanionProfileProjectionState profile() {
        return new CompanionProfileProjectionState(
                PROFILE,
                IMPORTED_ALIAS,
                LifecycleState.UNRESOLVED,
                null,
                null,
                "role",
                "Imported",
                null,
                true,
                null,
                null,
                Set.of(),
                Set.of(),
                -9_000
        );
    }

    private FakeAccess access(
            List<CompanionLifecycle> lifecycles,
            CompanionProfileProjectionState... profiles
    ) {
        return new FakeAccess(
                lifecycles,
                java.util.Arrays.stream(profiles).collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                CompanionProfileProjectionState::profileId,
                                value -> value
                        )
                )
        );
    }

    private static final class FakeAccess
            implements HytalePublicPersistenceWorldReconciliation.Access {
        private PersistenceReadResult<List<CompanionLifecycle>> read;
        private final Map<ProfileId, CompanionProfileProjectionState> profiles;
        private final List<CompanionProfileMutation.StartupReconciliation> submitted =
                new ArrayList<>();
        private final List<OperationId> operationIds = new ArrayList<>();
        private final List<IdempotencyKey> idempotencyKeys = new ArrayList<>();

        private FakeAccess(
                List<CompanionLifecycle> lifecycles,
                Map<ProfileId, CompanionProfileProjectionState> profiles
        ) {
            this.read = PersistenceReadResult.found(lifecycles, 0);
            this.profiles = profiles;
        }

        @Override
        public CompletionStage<PersistenceReadResult<List<CompanionLifecycle>>>
        findAllLifecycles() {
            return CompletableFuture.completedFuture(read);
        }

        @Override
        public Map<ProfileId, CompanionProfileProjectionState>
        projectedProfiles() {
            return profiles;
        }

        @Override
        public CompletionStage<Void> reconcileProfile(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation.StartupReconciliation reconciliation
        ) {
            operationIds.add(operationId);
            idempotencyKeys.add(idempotencyKey);
            submitted.add(reconciliation);
            return CompletableFuture.completedFuture(null);
        }
    }
}
