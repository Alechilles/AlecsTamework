package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.PlannedCompanionSpawnProbe;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Claims dormant population capacity before spawning one replacement coop resident. */
final class CoopPreparedReleaseSpawnService {
    private final CommandLinkedNpcCoopService coopService;
    private final CoopResidentSnapshotApplicationService snapshotApplicationService =
            new CoopResidentSnapshotApplicationService();
    private final CoopReleaseSpawnCompletion completion = new CoopReleaseSpawnCompletion();

    CoopPreparedReleaseSpawnService(@Nonnull CommandLinkedNpcCoopService coopService) {
        this.coopService = coopService;
    }

    boolean schedule(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d spawnPosition,
            @Nonnull Rotation3f spawnRotation,
            @Nonnull String roleId,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
            @Nonnull CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot ledger,
            boolean requireSnapshotOnRelease,
            @Nonnull Callbacks callbacks
    ) {
        CoopPopulationReleaseAdmissionService admissionService = resolveAdmissionService();
        if (admissionService == null || ledger.housedNpcUuid() == null) {
            deny(callbacks, "coop-release-population-authority-unavailable");
            return false;
        }
        UUID ownerId = resolveOwnerId(ledger.stateSnapshot(), ledger.ownerId());
        String ownerName = ledger.stateSnapshot() == null || ledger.stateSnapshot().owner() == null
                ? null
                : ledger.stateSnapshot().owner().getOwnerName();
        CoopPopulationReleaseAdmissionService.ReleaseRequest request =
                new CoopPopulationReleaseAdmissionService.ReleaseRequest(
                        ledger.housedNpcUuid(),
                        ownerId,
                        ownerName,
                        world.getName(),
                        ChunkUtil.chunkCoordinate(spawnPosition.x),
                        ChunkUtil.chunkCoordinate(spawnPosition.z),
                        "coop-release:" + ledger.housedNpcUuid() + ":" + slotIdentity(slotContext)
                );
        admissionService.prepareAsync(
                request,
                plannedNpcUuid -> CoopPopulationLedgerMutationJson.release(
                        coopService, slotContext, ledger, plannedNpcUuid
                )
        ).whenComplete((preparation, failure) -> {
            if (failure != null || preparation == null || !preparation.allowed()
                    || preparation.preparedRelease() == null) {
                String reason = preparation == null
                        ? "coop-release-population-prepare-failed"
                        : preparation.reason();
                dispatch(world, () -> deny(callbacks, reason), () -> deny(callbacks, reason));
                return;
            }
            CoopPopulationReleaseAdmissionService.PreparedRelease prepared =
                    preparation.preparedRelease();
            dispatch(
                    world,
                    () -> apply(
                            world, store, npcPlugin, roleIndex, spawnPosition, spawnRotation, roleId,
                            slotContext, ledger, requireSnapshotOnRelease,
                            admissionService, prepared, callbacks
                    ),
                    () -> {
                        cancelQuietly(
                                admissionService, prepared, "coop-release-world-unavailable"
                        );
                        deny(callbacks, "coop-release-world-unavailable");
                    }
            );
        });
        return true;
    }

    private void apply(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d spawnPosition,
            @Nonnull Rotation3f spawnRotation,
            @Nonnull String roleId,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
            @Nonnull CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot sourceLedger,
            boolean requireSnapshotOnRelease,
            @Nonnull CoopPopulationReleaseAdmissionService admissionService,
            @Nonnull CoopPopulationReleaseAdmissionService.PreparedRelease prepared,
            @Nonnull Callbacks callbacks
    ) {
        if (!sourceStillMatches(slotContext, sourceLedger)) {
            cancelQuietly(admissionService, prepared, "coop-release-source-resident-changed");
            deny(callbacks, "coop-release-source-resident-changed");
            return;
        }
        boolean claimed;
        try {
            claimed = admissionService.claimForSpawn(prepared);
        } catch (RuntimeException | LinkageError failure) {
            claimed = false;
        }
        if (!claimed) {
            cancelQuietly(admissionService, prepared, "coop-release-population-recheck-failed");
            deny(callbacks, "coop-release-population-recheck-failed");
            return;
        }
        SpawnAttempt attempt = spawn(
                world, store, npcPlugin, roleIndex, spawnPosition, spawnRotation,
                admissionService, prepared
        );
        Pair<Ref<EntityStore>, NPCEntity> spawned = attempt.spawned();
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            if (attempt.outcomeAmbiguous()) {
                admissionService.markReadinessDegraded("coop_release_spawn_outcome_ambiguous");
                degrade(callbacks, "coop-release-spawn-outcome-ambiguous");
                return;
            }
            despawnQuietly(spawned);
            cancelQuietly(admissionService, prepared, "coop-release-spawn-failed");
            deny(callbacks, "coop-release-spawn-failed");
            return;
        }
        if (!hasPlannedUuid(spawned.first(), store, prepared.plannedNpcUuid())) {
            despawnQuietly(spawned);
            admissionService.markReadinessDegraded("coop_release_spawn_identity_mismatch");
            degrade(callbacks, "coop-release-spawn-identity-mismatch");
            return;
        }
        CommandLinkedNpcCoopService.ReleaseResolution preview;
        try {
            preview = previewRelease(
                    prepared.request().previousNpcUuid(), roleId, slotContext,
                    sourceLedger, requireSnapshotOnRelease
            );
        } catch (RuntimeException | LinkageError failure) {
            despawnQuietly(spawned);
            admissionService.markReadinessDegraded("coop_release_ledger_preview_failed_live");
            degrade(callbacks, "coop-release-ledger-preview-failed-live");
            return;
        }
        if (preview.isFailure()) {
            despawnQuietly(spawned);
            admissionService.markReadinessDegraded("coop_release_ledger_preview_rejected_live");
            degrade(callbacks, preview.failureReason());
            return;
        }
        try {
            applySnapshot(spawned.first(), store, preview);
        } catch (RuntimeException | LinkageError failure) {
            despawnQuietly(spawned);
            admissionService.markReadinessDegraded("coop_release_snapshot_apply_failed_live");
            degrade(callbacks, "coop-release-snapshot-apply-failed-live");
            return;
        }
        completion.finish(
                () -> admissionService.commitAsync(prepared),
                ignored -> finishDurableRelease(
                        world, admissionService, prepared, roleId, slotContext,
                        requireSnapshotOnRelease, callbacks
                ),
                callbacks::onDurabilityDegraded,
                callbacks::onTerminal,
                (applied, rejected) -> dispatch(world, applied, rejected)
        );
    }

    private void finishDurableRelease(
            World world,
            CoopPopulationReleaseAdmissionService admissionService,
            CoopPopulationReleaseAdmissionService.PreparedRelease prepared,
            String roleId,
            CommandLinkedNpcCoopService.CoopSlotContext slotContext,
            boolean requireSnapshotOnRelease,
            Callbacks callbacks
    ) {
        Store<EntityStore> liveStore = world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        PlannedCompanionSpawnProbe.Result live = liveStore == null ? null
                : PlannedCompanionSpawnProbe.probe(
                        world, liveStore, prepared.plannedNpcUuid()
                );
        if (live == null || !live.present() || !admissionService.matchesLiveIdentity(
                prepared, live.ref(), liveStore
        )) {
            admissionService.markReadinessDegraded(
                    "coop_release_live_identity_changed_before_continuation"
            );
            callbacks.onDurabilityDegraded(
                    "coop-release-live-target-unavailable-after-commit"
            );
            return;
        }
        CommandLinkedNpcCoopService.ReleaseResolution released =
                coopService.resolveReleaseInPopulationCommit(
                        prepared.plannedNpcUuid(), prepared.request().previousNpcUuid(),
                        roleId, slotContext, requireSnapshotOnRelease
                );
        if (released.isFailure()) {
            callbacks.onDurabilityDegraded(
                    "coop-release-in-memory-ledger-sync-failed:" + released.failureReason()
            );
            return;
        }
        callbacks.onReleased(
                released, prepared.profileId(), prepared.plannedNpcUuid(),
                live.ref(), live.npc()
        );
    }

    private CommandLinkedNpcCoopService.ReleaseResolution previewRelease(
            UUID expectedHousedNpcUuid,
            String roleId,
            CommandLinkedNpcCoopService.CoopSlotContext slotContext,
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot expected,
            boolean requireSnapshotOnRelease
    ) {
        if (!sourceStillMatches(slotContext, expected)
                || !expectedHousedNpcUuid.equals(expected.housedNpcUuid())) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure(
                    "source_resident_changed"
            );
        }
        if (expected.roleId() != null && !expected.roleId().equals(roleId)) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("role_mismatch");
        }
        if (requireSnapshotOnRelease && expected.stateSnapshot() == null) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("snapshot_missing");
        }
        return new CommandLinkedNpcCoopService.ReleaseResolution(
                expectedHousedNpcUuid,
                expected.stateSnapshot(),
                coopService.getCoopSnapshot(expectedHousedNpcUuid),
                false,
                null
        );
    }

    @Nonnull
    private SpawnAttempt spawn(
            World world,
            Store<EntityStore> store,
            NPCPlugin npcPlugin,
            int roleIndex,
            Vector3d position,
            Rotation3f rotation,
            CoopPopulationReleaseAdmissionService admissionService,
            CoopPopulationReleaseAdmissionService.PreparedRelease prepared
    ) {
        try {
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                    store, roleIndex, position, rotation, null,
                    (npc, holder, spawnStore) -> {
                        OwnerComponentMutationService.WriteResult write =
                                admissionService.writeSpawnHolder(prepared, holder);
                        if (!write.applied()) {
                            throw new SpawnPreparationException(write.reason());
                        }
                    },
                    null
            );
            return spawned != null && spawned.first() != null && spawned.second() != null
                    ? SpawnAttempt.spawned(spawned)
                    : recoverSpawn(world, store, prepared.plannedNpcUuid());
        } catch (SpawnPreparationException exception) {
            return SpawnAttempt.absent();
        } catch (RuntimeException | LinkageError exception) {
            return recoverSpawn(world, store, prepared.plannedNpcUuid());
        }
    }

    @Nonnull
    private static SpawnAttempt recoverSpawn(World world,
                                             Store<EntityStore> store,
                                             UUID plannedNpcUuid) {
        PlannedCompanionSpawnProbe.Result probe =
                PlannedCompanionSpawnProbe.probe(world, store, plannedNpcUuid);
        if (probe.present()) {
            return SpawnAttempt.spawned(Pair.of(probe.ref(), probe.npc()));
        }
        return probe.absenceProven() ? SpawnAttempt.absent() : SpawnAttempt.ambiguous();
    }

    private boolean sourceStillMatches(
            CommandLinkedNpcCoopService.CoopSlotContext context,
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot expected
    ) {
        try {
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot current =
                    coopService.getLedgerSlotSnapshot(context);
            return current != null
                    && Objects.equals(current.housedNpcUuid(), expected.housedNpcUuid())
                    && Objects.equals(current.ownerId(), expected.ownerId())
                    && Objects.equals(current.roleId(), expected.roleId())
                    && current.housedAtMs() == expected.housedAtMs();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static boolean hasPlannedUuid(
            Ref<EntityStore> spawnedRef,
            Store<EntityStore> store,
            UUID plannedUuid
    ) {
        if (spawnedRef == null || !spawnedRef.isValid()) {
            return false;
        }
        try {
            UUIDComponent component = store.getComponent(
                    spawnedRef, UUIDComponent.getComponentType()
            );
            return component != null && plannedUuid.equals(component.getUuid());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static void despawnQuietly(@Nullable Pair<Ref<EntityStore>, NPCEntity> spawned) {
        try {
            if (spawned != null && spawned.second() != null) {
                spawned.second().setToDespawn();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Population cancellation and startup reconciliation cover despawn ambiguity.
        }
    }

    private static void cancelQuietly(
            CoopPopulationReleaseAdmissionService service,
            CoopPopulationReleaseAdmissionService.PreparedRelease prepared,
            String reason
    ) {
        try {
            service.cancelAsync(prepared, reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The prepared journal remains recoverable on restart.
        }
    }

    private void applySnapshot(Ref<EntityStore> ref,
                               Store<EntityStore> store,
                               CommandLinkedNpcCoopService.ReleaseResolution resolution) {
        if (resolution.stateSnapshot() != null) {
            snapshotApplicationService.applyDirect(ref, store, resolution.stateSnapshot());
        } else {
            snapshotApplicationService.applyLinkedFallbackDirect(ref, store, resolution.linkedSnapshot());
        }
    }

    @Nullable
    private static CoopPopulationReleaseAdmissionService resolveAdmissionService() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null || plugin.getOwnerPopulationRuntime() == null
                ? null
                : plugin.getOwnerPopulationRuntime().coopReleaseAdmissionService();
    }

    @Nullable
    private static UUID resolveOwnerId(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            @Nullable UUID fallback
    ) {
        if (snapshot != null && snapshot.owner() != null && snapshot.owner().getOwnerId() != null) {
            return snapshot.owner().getOwnerId();
        }
        return snapshot != null && snapshot.commandLinks() != null
                && snapshot.commandLinks().getOwnerId() != null
                ? snapshot.commandLinks().getOwnerId()
                : fallback;
    }

    private static String slotIdentity(CommandLinkedNpcCoopService.CoopSlotContext context) {
        return context.worldName() + ":" + context.coopId() + ":" + context.x() + ":"
                + context.y() + ":" + context.z() + ":" + context.residentSlot();
    }

    private static void dispatch(World world, Runnable applied) {
        dispatch(world, applied, () -> {
        });
    }

    private static void dispatch(World world, Runnable applied, Runnable rejected) {
        try {
            if (!world.isAlive()) {
                rejected.run();
            } else {
                world.execute(applied);
            }
        } catch (RuntimeException | LinkageError failure) {
            rejected.run();
        }
    }

    private static void deny(Callbacks callbacks, String reason) {
        try {
            callbacks.onDenied(reason == null ? "coop-release-denied" : reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Terminal cleanup below must run even if notification code is faulty.
        } finally {
            try {
                callbacks.onTerminal();
            } catch (RuntimeException | LinkageError ignored) {
                // The admission has already been cancelled or was never prepared.
            }
        }
    }

    private static void degrade(Callbacks callbacks, String reason) {
        try {
            callbacks.onDurabilityDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The APPLYING journal remains authoritative even when diagnostics fail.
        } finally {
            try {
                callbacks.onTerminal();
            } catch (RuntimeException | LinkageError ignored) {
                // Runtime bookkeeping is best effort after readiness is quarantined.
            }
        }
    }

    private record SpawnAttempt(@Nullable Pair<Ref<EntityStore>, NPCEntity> spawned,
                                boolean outcomeAmbiguous) {
        @Nonnull
        private static SpawnAttempt spawned(@Nonnull Pair<Ref<EntityStore>, NPCEntity> spawned) {
            return new SpawnAttempt(spawned, false);
        }

        @Nonnull
        private static SpawnAttempt absent() {
            return new SpawnAttempt(null, false);
        }

        @Nonnull
        private static SpawnAttempt ambiguous() {
            return new SpawnAttempt(null, true);
        }
    }

    interface Callbacks {
        void onReleased(@Nonnull CommandLinkedNpcCoopService.ReleaseResolution resolution,
                        @Nonnull String profileId,
                        @Nonnull UUID currentNpcUuid,
                        @Nonnull Ref<EntityStore> spawnedRef,
                        @Nonnull NPCEntity spawnedNpc);

        void onDenied(@Nonnull String reason);

        default void onDurabilityDegraded(@Nonnull String reason) {
        }

        default void onTerminal() {
        }
    }

    private static final class SpawnPreparationException extends RuntimeException {
        private SpawnPreparationException(String reason) {
            super(reason);
        }
    }
}
