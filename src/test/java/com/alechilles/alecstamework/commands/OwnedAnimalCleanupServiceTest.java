package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.*;
import com.alechilles.alecstamework.companion.lifecycle.*;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedAnimalCleanupServiceTest {
    @Test void waitsForLiveCleanupBeforeStartingTheNextProfile() throws Exception {
        UUID owner = UUID.randomUUID();
        var first = profile(owner, LifecycleState.ACTIVE);
        var second = profile(owner, LifecycleState.UNLOADED);
        var cleanupStarted = new CompletableFuture<Void>();
        var finishCleanup = new CompletableFuture<Void>();
        var port = new TestPort(first, second) {
            @Override public CompletionStage<Void> cleanup(CompanionProfileReadModel profile) {
                super.cleanup(profile);
                if (profile.identity().profileId().equals(first.identity().profileId())) {
                    cleanupStarted.complete(null);
                    return finishCleanup;
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        var completion = new OwnedAnimalCleanupService(port).clear(owner,
                List.of(first.identity().profileId(), second.identity().profileId())).toCompletableFuture();
        cleanupStarted.get(5, TimeUnit.SECONDS);
        assertFalse(completion.isDone());
        assertEquals(List.of(first.identity().profileId()), port.released);
        finishCleanup.complete(null);
        assertEquals(2, completion.get(5, TimeUnit.SECONDS).cleared());
        assertEquals(List.of(first.identity().profileId(), second.identity().profileId()), port.cleaned);
    }

    @Test void rechecksOwnershipAndStorageBeforeDeleting() throws Exception {
        UUID owner = UUID.randomUUID();
        var dead = profile(owner, LifecycleState.DEAD_REVIVABLE);
        var transferred = profile(UUID.randomUUID(), LifecycleState.DEAD_REVIVABLE);
        var captured = profile(owner, LifecycleState.CAPTURED);
        var port = new TestPort(dead, transferred, captured);
        var result = new OwnedAnimalCleanupService(port).clear(owner,
                List.of(dead.identity().profileId(), transferred.identity().profileId(), captured.identity().profileId()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, result.cleared());
        assertEquals(2, result.skipped());
        assertEquals(List.of(dead.identity().profileId()), port.released);
        assertEquals(port.released, port.cleaned);
    }

    @Test void rejectedDurableReleaseNeverRemovesTheLiveAnimal() throws Exception {
        UUID owner = UUID.randomUUID();
        var live = profile(owner, LifecycleState.ACTIVE);
        var port = new TestPort(live);
        port.allowRelease = false;
        var result = new OwnedAnimalCleanupService(port).clear(owner, List.of(live.identity().profileId()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, result.failed());
        assertEquals(0, result.cleared());
        assertTrue(port.cleaned.isEmpty());
    }

    static CompanionProfileReadModel profile(UUID owner, LifecycleState state) {
        var id = new ProfileId(UUID.randomUUID());
        var alias = new NpcAlias(UUID.randomUUID());
        var location = state == LifecycleState.ACTIVE
                ? LifecycleLocation.liveEntity(alias.toString(), "world")
                : state == LifecycleState.CAPTURED
                ? LifecycleLocation.keyed(LifecycleLocationKind.CAPTURE_ITEM, "capture") : LifecycleLocation.none();
        return new CompanionProfileReadModel(new CompanionIdentity(id, "Cow", "Cow", null, null, "world", -10, -10, -10, 0),
                new CompanionAlias(alias, id, 0, CompanionAlias.State.CURRENT, null, -10, null),
                new CompanionLifecycle(id, new OwnerId(owner), state, location, new LifecycleRevision(0), null,
                        -10, new ReconciliationGeneration(0), null), List.of(), List.of(), null);
    }

    static class TestPort implements OwnedAnimalCleanupService.Port {
        final Map<ProfileId, CompanionProfileReadModel> profiles = new HashMap<>();
        final List<ProfileId> released = new ArrayList<>();
        final List<ProfileId> cleaned = new ArrayList<>();
        boolean allowRelease = true;
        TestPort(CompanionProfileReadModel... values) {
            for (var value : values) profiles.put(value.identity().profileId(), value);
        }
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> read(ProfileId id) {
            return CompletableFuture.completedFuture(new PersistenceReadResult.Found<>(profiles.get(id), 0));
        }
        public CompletionStage<Boolean> release(OwnerPopulationTransitionRequest request) {
            assertNull(request.targetOwnerId());
            assertEquals(-9, request.requestedAtMs());
            released.add(request.profileId());
            return CompletableFuture.completedFuture(allowRelease);
        }
        public CompletionStage<Void> cleanup(CompanionProfileReadModel profile) {
            cleaned.add(profile.identity().profileId());
            return CompletableFuture.completedFuture(null);
        }
    }
}
