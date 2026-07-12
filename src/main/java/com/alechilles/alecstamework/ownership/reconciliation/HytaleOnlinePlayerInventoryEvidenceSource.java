package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * World-thread snapshot of online inventory sections, run after persisted player-save coverage.
 */
public final class HytaleOnlinePlayerInventoryEvidenceSource implements CompanionPopulationEvidenceSource {
    private static final String COVERAGE_KEY = "player-saves:online";
    private final Universe universe;
    private final HytalePlayerInventoryEvidenceScanner inventories;
    private final List<Target> targets;
    private final Descriptor descriptor;

    public HytaleOnlinePlayerInventoryEvidenceSource(
            @Nonnull Universe universe,
            @Nonnull HytalePlayerInventoryEvidenceScanner inventories
    ) {
        this(universe, inventories, "direct-source");
    }

    public HytaleOnlinePlayerInventoryEvidenceSource(
            @Nonnull Universe universe,
            @Nonnull HytalePlayerInventoryEvidenceScanner inventories,
            @Nonnull String mutableSourceEpoch
    ) {
        this.universe = Objects.requireNonNull(universe, "universe");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.targets = currentTargets(universe);
        List<UUID> generationValues = new ArrayList<>(targets.size() * 2);
        for (Target target : targets) {
            generationValues.add(target.playerUuid());
            generationValues.add(target.worldUuid());
        }
        this.descriptor = new Descriptor(
                COVERAGE_KEY,
                com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                "universe",
                ReconciliationGeneration.forStrings(
                        COVERAGE_KEY,
                        List.of(
                                ReconciliationGeneration.forUuids(COVERAGE_KEY, generationValues),
                                requireText(mutableSourceEpoch, "mutableSourceEpoch")
                        )
                ),
                targets.size()
        );
    }

    @Nonnull
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Nonnull
    @Override
    public CompletableFuture<Batch> scan(long offset, int maxUnits) {
        int start = checkedStart(offset);
        int end = Math.min(targets.size(), start + requirePositive(maxUnits));
        CompletableFuture<List<CompanionPopulationEvidence>> future =
                CompletableFuture.completedFuture(new ArrayList<>());
        for (int index = start; index < end; index++) {
            Target target = targets.get(index);
            future = future.thenCompose(evidence -> scanTarget(target).thenApply(found -> {
                evidence.addAll(found);
                return evidence;
            }));
        }
        return future.thenApply(evidence -> {
            boolean complete = end == targets.size();
            if (complete && !targets.equals(currentTargets(universe))) {
                throw new IllegalStateException("Online-player catalog changed during reconciliation.");
            }
            return new Batch(evidence, end, end - start, complete);
        });
    }

    @Nonnull
    private CompletableFuture<List<CompanionPopulationEvidence>> scanTarget(@Nonnull Target target) {
        CompletableFuture<List<CompanionPopulationEvidence>> result = new CompletableFuture<>();
        World world = universe.getWorld(target.worldUuid());
        if (world == null || !world.isAlive()) {
            result.completeExceptionally(new IllegalStateException(
                    "Online player world disappeared during reconciliation: " + target.playerUuid()
            ));
            return result;
        }
        executeOrFail((task, rejected) -> LeaseBoundWorldDispatcher.execute(
                world, task, rejected
        ), () -> {
                try {
                    Ref<EntityStore> playerRef = world.getEntityRef(target.playerUuid());
                    if (playerRef == null || !playerRef.isValid()) {
                        throw new IllegalStateException(
                                "Online player entity disappeared during reconciliation: " + target.playerUuid()
                        );
                    }
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    result.complete(inventories.scan(
                            store,
                            playerRef,
                            "online-player/" + target.playerUuid(),
                            COVERAGE_KEY
                    ));
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            }, result);
        return result;
    }

    static void executeOrFail(@Nonnull WorldDispatch dispatcher,
                              @Nonnull Runnable task,
                              @Nonnull CompletableFuture<?> result) {
        try {
            dispatcher.dispatch(task, () -> result.completeExceptionally(
                    new IllegalStateException(
                            "Online player world dispatch did not start before its lease expired."
                    )
            ));
        } catch (RuntimeException | LinkageError failure) {
            result.completeExceptionally(failure);
        }
    }

    private int checkedStart(long offset) {
        if (offset < 0L || offset > targets.size()) {
            throw new IllegalArgumentException("Online-player cursor is outside the source snapshot.");
        }
        return Math.toIntExact(offset);
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxUnits must be positive.");
        }
        return value;
    }

    @Nonnull
    private static List<Target> currentTargets(@Nonnull Universe universe) {
        List<Target> snapshot = new ArrayList<>();
        for (PlayerRef player : universe.getPlayers()) {
            snapshot.add(new Target(player.getUuid(), player.getWorldUuid()));
        }
        snapshot.sort(Comparator.comparing(target -> target.playerUuid().toString()));
        return List.copyOf(snapshot);
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    private record Target(@Nonnull UUID playerUuid, @Nonnull UUID worldUuid) {
    }

    @FunctionalInterface
    interface WorldDispatch {
        void dispatch(@Nonnull Runnable task, @Nonnull Runnable rejected);
    }
}
