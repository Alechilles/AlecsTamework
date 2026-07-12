package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPermanentDeathCoordinatorTest {
    @Test
    void durabilityFailureKeepsCorpseBarrierUntilCanonicalReleaseCommits() {
        Harness harness = schedulePermanentDeath();

        assertTrue(harness.coordinator().isPending(harness.npcUuid()));

        harness.callbacks().onDurabilityDegraded("sqlite-write-failed");
        assertTrue(harness.coordinator().isPending(harness.npcUuid()));

        harness.callbacks().onApplyCompensated(
                "profile-id",
                "owner-component-write-ambiguous",
                null
        );
        assertTrue(harness.coordinator().isPending(harness.npcUuid()));

        harness.callbacks().onPopulationCommitted(new CompanionPopulationCommitResult(
                false,
                "owner-population-commit-failed",
                true,
                new OwnerPopulationCommitResult(
                        OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                        "owner-population-commit-failed",
                        null
                )
        ));
        assertTrue(harness.coordinator().isPending(harness.npcUuid()));

        harness.callbacks().onPopulationCommitted(new CompanionPopulationCommitResult(
                true,
                "companion-population-committed",
                true,
                new OwnerPopulationCommitResult(
                        OwnerPopulationCommitResult.Status.COMMITTED,
                        "owner-population-committed",
                        null
                )
        ));
        assertFalse(harness.coordinator().isPending(harness.npcUuid()));
    }

    @Test
    void denialBeforeLiveApplyReleasesPendingBarrierForSafeRetry() {
        Harness harness = schedulePermanentDeath();

        harness.callbacks().onApplyCompensated(
                "profile-id",
                "owner-component-state-changed",
                null
        );
        assertFalse(harness.coordinator().isPending(harness.npcUuid()));

        harness = schedulePermanentDeath();
        harness.callbacks().onDenied("owner-mutation-continuation-rejected", null);

        assertFalse(harness.coordinator().isPending(harness.npcUuid()));
    }

    @Test
    void rejectedPreApplyWorldDispatchClearsBarrierAndEnablesRetry() {
        Harness harness = schedulePermanentDeath();

        harness.callbacks().onWorldDispatchRejected(
                "owner-mutation-world-unavailable", false, null
        );

        assertFalse(harness.coordinator().isPending(harness.npcUuid()));
        assertTrue(harness.coordinator().interceptLethalDamage(
                null,
                null,
                harness.npcUuid(),
                UUID.randomUUID(),
                new Damage(Damage.NULL_SOURCE, 0, 10.0f),
                10.0f
        ));
        assertTrue(harness.coordinator().isPending(harness.npcUuid()));
    }

    @Test
    void rejectedPostApplyDispatchOnlyClearsAfterDurableRelease() {
        Harness harness = schedulePermanentDeath();

        harness.callbacks().onWorldDispatchRejected(
                "owner-mutation-world-unavailable", true, failedCommit()
        );
        assertTrue(harness.coordinator().isPending(harness.npcUuid()));

        harness.callbacks().onWorldDispatchRejected(
                "owner-mutation-world-unavailable", true, committedRelease()
        );
        assertFalse(harness.coordinator().isPending(harness.npcUuid()));
    }

    private static CompanionPopulationCommitResult failedCommit() {
        return new CompanionPopulationCommitResult(
                false,
                "owner-population-commit-failed",
                true,
                new OwnerPopulationCommitResult(
                        OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                        "owner-population-commit-failed",
                        null
                )
        );
    }

    private static CompanionPopulationCommitResult committedRelease() {
        return new CompanionPopulationCommitResult(
                true,
                "companion-population-committed",
                true,
                new OwnerPopulationCommitResult(
                        OwnerPopulationCommitResult.Status.COMMITTED,
                        "owner-population-committed",
                        null
                )
        );
    }

    private static Harness schedulePermanentDeath() {
        AtomicReference<OwnerMutationScheduler.MutationCallbacks> captured =
                new AtomicReference<>();
        CompanionPermanentDeathCoordinator coordinator =
                new CompanionPermanentDeathCoordinator(
                        (ref, store, key, context, callbacks) -> {
                            captured.set(callbacks);
                            return true;
                        }
                );
        UUID npcUuid = UUID.randomUUID();
        Damage damage = new Damage(Damage.NULL_SOURCE, 0, 10.0f);

        assertTrue(coordinator.interceptLethalDamage(
                null,
                null,
                npcUuid,
                UUID.randomUUID(),
                damage,
                10.0f
        ));
        OwnerMutationScheduler.MutationCallbacks callbacks = captured.get();
        assertNotNull(callbacks);
        return new Harness(coordinator, npcUuid, callbacks);
    }

    private record Harness(CompanionPermanentDeathCoordinator coordinator,
                           UUID npcUuid,
                           OwnerMutationScheduler.MutationCallbacks callbacks) {
    }
}
