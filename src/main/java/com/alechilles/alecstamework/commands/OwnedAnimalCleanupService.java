package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One finite admin cleanup: one durable operation and live cleanup in flight at a time. */
final class OwnedAnimalCleanupService {
    interface Port {
        CompletionStage<PersistenceReadResult<CompanionProfileReadModel>> read(ProfileId id);
        CompletionStage<Boolean> release(OwnerPopulationTransitionRequest request);
        CompletionStage<Void> cleanup(CompanionProfileReadModel profile);
    }

    record Result(int cleared, int skipped, int failed) {
        Result plus(Result other) {
            return new Result(cleared + other.cleared, skipped + other.skipped, failed + other.failed);
        }
    }

    private final Port port;

    OwnedAnimalCleanupService(Port port) { this.port = port; }

    CompletionStage<Result> clear(UUID owner, List<ProfileId> profiles) {
        CompletionStage<Result> result = CompletableFuture.completedFuture(new Result(0, 0, 0));
        for (ProfileId profile : profiles) {
            // Yield between profiles; never loop through persistence work on a world thread.
            result = result.thenComposeAsync(total -> clearOne(owner, profile).thenApply(total::plus));
        }
        return result;
    }

    private CompletionStage<Result> clearOne(UUID owner, ProfileId id) {
        try {
            return port.read(id).thenCompose(read -> {
                if (read instanceof PersistenceReadResult.Absent<?>) return result(0, 1, 0);
                if (!(read instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found)) {
                    return result(0, 0, 1);
                }
                var profile = found.value();
                var state = profile.lifecycle();
                if (!id.equals(state.profileId()) || state.ownerId() == null
                        || !owner.equals(state.ownerId().value()) || !eligible(state.state())) {
                    return result(0, 1, 0);
                }
                if (state.activeOperationId() != null || state.quarantined()) return result(0, 0, 1);
                var request = new OwnerPopulationTransitionRequest(id, state.revision(), state.ownerId(),
                        state.ownerWorldKey(), null, null, 0, 0, Math.addExact(state.stateChangedAtMs(), 1L));
                return port.release(request).thenCompose(published -> published
                        ? port.cleanup(profile).thenApply(ignored -> new Result(1, 0, 0))
                        : result(0, 0, 1));
            }).exceptionally(failure -> new Result(0, 0, 1));
        } catch (RuntimeException failure) {
            return result(0, 0, 1);
        }
    }

    static boolean eligible(LifecycleState state) {
        return state == LifecycleState.ACTIVE || state == LifecycleState.UNLOADED
                || state == LifecycleState.DEAD_REVIVABLE || state == LifecycleState.LOST;
    }

    private static CompletionStage<Result> result(int cleared, int skipped, int failed) {
        return CompletableFuture.completedFuture(new Result(cleared, skipped, failed));
    }
}
