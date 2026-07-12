package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopChunkScanner.ScanResult;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchOutcome;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeSweepPlanner.CoopPlan;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeSweepPlanner.SweepPlan;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thin live sweep shell over the decomposed managed-coop scanners, planner, and v5 dispatcher.
 *
 * <p>Ancillary work receives only copied requests. Production is gated by the one release future
 * selected for that coop, allowing its implementation to re-resolve the world and current v5
 * HOUSED residents after the attempt settles. This orchestrator never attaches a continuation
 * that captures a world, store, reference, context, block container, or NPC.</p>
 */
public final class ManagedCoopRuntimeSweepOrchestrator {
    public enum SweepStatus {
        COMPLETED,
        WORLD_UNAVAILABLE,
        CONTEXT_SCAN_UNAVAILABLE
    }

    public record SweepOutcome(@Nonnull SweepStatus status,
                               int managedCoops,
                               int captureCandidates,
                               int captureDispatches,
                               int releaseDispatches,
                               int importBlockedCoops,
                               boolean lifecycleRecoveryAttempted,
                               boolean removedCheckDispatched,
                               @Nonnull List<CompletableFuture<DispatchOutcome>> operations,
                               @Nullable String detail) {
        public SweepOutcome {
            Objects.requireNonNull(status, "status");
            operations = List.copyOf(operations);
        }
    }

    private final ManagedCoopChunkScanner contextScanner;
    private final ManagedCoopRuntimeCandidateScanner candidateScanner;
    private final ManagedCoopRuntimeSweepPlanner planner;
    private final ManagedCoopRuntimeOperationDispatcher operations;
    private final ImportBehavior imports;
    private final LifecycleRecoveryBehavior lifecycleRecovery;
    private final AncillaryBehavior ancillary;
    private final RemovedCoopReconciler removedCoops;

    public ManagedCoopRuntimeSweepOrchestrator(
            @Nonnull ManagedCoopChunkScanner contextScanner,
            @Nonnull ManagedCoopRuntimeCandidateScanner candidateScanner,
            @Nonnull ManagedCoopRuntimeSweepPlanner planner,
            @Nonnull ManagedCoopRuntimeOperationDispatcher operations,
            @Nonnull ImportBehavior imports,
            @Nonnull LifecycleRecoveryBehavior lifecycleRecovery,
            @Nonnull AncillaryBehavior ancillary,
            @Nonnull RemovedCoopReconciler removedCoops) {
        this.contextScanner = Objects.requireNonNull(contextScanner, "contextScanner");
        this.candidateScanner = Objects.requireNonNull(candidateScanner, "candidateScanner");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.lifecycleRecovery = Objects.requireNonNull(
                lifecycleRecovery, "lifecycleRecovery");
        this.ancillary = Objects.requireNonNull(ancillary, "ancillary");
        this.removedCoops = Objects.requireNonNull(removedCoops, "removedCoops");
    }

    /** Executes one already-throttled world sweep on the chunk/entity stores' owning thread. */
    @Nonnull
    public SweepOutcome sweep(@Nonnull Store<ChunkStore> chunkStore,
                              @Nonnull World world,
                              int gameHour,
                              long gameTimeMs,
                              long nowMs) {
        Objects.requireNonNull(chunkStore, "chunkStore");
        Objects.requireNonNull(world, "world");
        Store<EntityStore> entityStore = entityStore(world);
        if (entityStore == null) {
            return outcome(SweepStatus.WORLD_UNAVAILABLE, 0, 0, 0, 0, 0, false, false,
                    List.of(), "managed_coop_world_or_entity_store_unavailable");
        }
        entityStore.assertThread();
        ScanResult contexts = contextScanner.scan(chunkStore, world);
        if (!contexts.reliable()) {
            return outcome(SweepStatus.CONTEXT_SCAN_UNAVAILABLE, 0, 0, 0, 0, 0, false, false,
                    List.of(), contexts.detail());
        }

        Set<String> physicalCoopKeys = activeCoopKeys(contexts.contexts());
        ImportFilter imported = filterImports(
                chunkStore, world, contexts.contexts(), nowMs);
        // Import runs first, but pre-existing capture/release operations must still recover or an
        // import gate waiting for those operations would deadlock the authority indefinitely.
        boolean recoveryAttempted = startLifecycleRecovery(world, contexts.contexts());

        boolean captureDemand = planner.needsCaptureCandidates(
                imported.ready(), gameHour, nowMs);
        ManagedCoopRuntimeCandidateScanner.ScanResult candidates = captureDemand
                ? candidateScanner.scan(entityStore)
                : new ManagedCoopRuntimeCandidateScanner.ScanResult(
                        ManagedCoopRuntimeCandidateScanner.ScanStatus.COMPLETE,
                        List.of(), 0, 0, null);
        List<ManagedCoopCaptureCandidate> candidateValues =
                candidates.status() == ManagedCoopRuntimeCandidateScanner.ScanStatus.COMPLETE
                        ? candidates.candidates() : List.of();
        SweepPlan plan = planner.plan(
                imported.ready(), candidateValues, gameHour, nowMs, true);
        ArrayList<CompletableFuture<DispatchOutcome>> dispatched = new ArrayList<>();
        int captures = 0;
        int releases = 0;
        for (CoopPlan coop : plan.coops()) {
            ManagedCoopAncillaryRequest ancillaryRequest =
                    ManagedCoopAncillaryRequest.copyOf(coop.context(), gameTimeMs);
            CompletableFuture<DispatchOutcome> precedingRelease = null;
            if (coop.branch() == ManagedCoopRuntimeSweepPlanner.Branch.CAPTURE) {
                Ref<EntityStore> source = world.getEntityRef(coop.candidate().npcUuid());
                if (source != null && source.isValid()) {
                    // capture() consumes the live arguments synchronously before returning.
                    dispatched.add(operations.capture(
                            entityStore, source, coop.context(), coop.candidate()));
                    captures++;
                }
            } else if (coop.branch() == ManagedCoopRuntimeSweepPlanner.Branch.RELEASE) {
                precedingRelease = operations.release(coop.context(), coop.resident(), nowMs);
                dispatched.add(precedingRelease);
                releases++;
            }
            if (coop.produce()) {
                ancillary.produceAfter(ancillaryRequest, precedingRelease);
            }
            if (coop.syncInteractionState()) {
                ancillary.syncInteractionState(ancillaryRequest);
            }
        }
        ancillary.retainActiveCoops(physicalCoopKeys);
        if (plan.checkRemovedCoops()) {
            removedCoops.reconcile(chunkStore, world, physicalCoopKeys, nowMs);
        }
        String candidateDetail = candidates.status()
                == ManagedCoopRuntimeCandidateScanner.ScanStatus.COMPLETE
                ? null : candidates.detail();
        return outcome(
                SweepStatus.COMPLETED,
                contexts.contexts().size(),
                candidateValues.size(),
                captures,
                releases,
                imported.blocked(),
                recoveryAttempted,
                plan.checkRemovedCoops(),
                dispatched,
                candidateDetail);
    }

    @Nullable
    private Store<EntityStore> entityStore(World world) {
        return world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
    }

    private SweepOutcome outcome(SweepStatus status,
                                 int coops,
                                 int candidates,
                                 int captures,
                                 int releases,
                                 int importBlocked,
                                 boolean recoveryAttempted,
                                 boolean removed,
                                 List<CompletableFuture<DispatchOutcome>> operations,
                                 @Nullable String detail) {
        return new SweepOutcome(
                status, coops, candidates, captures, releases, importBlocked,
                recoveryAttempted, removed, operations, detail);
    }

    private ImportFilter filterImports(Store<ChunkStore> chunkStore,
                                       World world,
                                       List<ManagedCoopContext> contexts,
                                       long nowMs) {
        ArrayList<ManagedCoopContext> ready = new ArrayList<>();
        int blocked = 0;
        for (ManagedCoopContext context : contexts) {
            if (context == null || importBlocks(chunkStore, world, context, nowMs)) {
                blocked++;
            } else {
                ready.add(context);
            }
        }
        return new ImportFilter(List.copyOf(ready), blocked);
    }

    private boolean importBlocks(Store<ChunkStore> chunkStore,
                                 World world,
                                 ManagedCoopContext context,
                                 long nowMs) {
        try {
            ImportDecision decision = imports.inspect(chunkStore, world, context, nowMs);
            return decision == null || decision.blocksManagedRuntime();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private Set<String> activeCoopKeys(List<ManagedCoopContext> contexts) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ManagedCoopContext context : contexts) {
            if (context != null) {
                keys.add(context.coopKey());
            }
        }
        return Set.copyOf(keys);
    }

    private boolean startLifecycleRecovery(World world, List<ManagedCoopContext> contexts) {
        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        try {
            CompletionStage<?> stage = lifecycleRecovery.recover(worldName, contexts);
            return stage != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Restart-recovery seam. Implementations must consume contexts synchronously and retain only
     * immutable operation/site values after returning their completion stage.
     */
    @FunctionalInterface
    public interface LifecycleRecoveryBehavior {
        @Nullable
        CompletionStage<?> recover(
                @Nonnull String worldName,
                @Nonnull List<ManagedCoopContext> contexts);
    }

    /** Synchronous import gate that must run before every other operation for the context. */
    @FunctionalInterface
    public interface ImportBehavior {
        @Nullable
        ImportDecision inspect(
                @Nonnull Store<ChunkStore> chunkStore,
                @Nonnull World world,
                @Nonnull ManagedCoopContext context,
                long nowMs);
    }

    /** Immutable import result; absent or exceptional evidence is treated as blocking. */
    public record ImportDecision(boolean blocksManagedRuntime,
                                 @Nullable String detail) {
    }

    /** Immutable ancillary seam; implementations re-resolve live block state by world name. */
    public interface AncillaryBehavior {
        void produceAfter(
                @Nonnull ManagedCoopAncillaryRequest request,
                @Nullable CompletionStage<DispatchOutcome> precedingRelease);

        void syncInteractionState(@Nonnull ManagedCoopAncillaryRequest request);

        void retainActiveCoops(@Nonnull Set<String> activeCoopKeys);
    }

    /**
     * Synchronous removal-detection seam. Implementations must release confirmed removed-coop
     * residents through {@link ManagedCoopRuntimeOperationDispatcher}, never a legacy ledger.
     */
    @FunctionalInterface
    public interface RemovedCoopReconciler {
        void reconcile(@Nonnull Store<ChunkStore> chunkStore,
                       @Nonnull World world,
                       @Nonnull Set<String> activeCoopKeys,
                       long nowMs);
    }

    private record ImportFilter(@Nonnull List<ManagedCoopContext> ready, int blocked) {
    }
}
