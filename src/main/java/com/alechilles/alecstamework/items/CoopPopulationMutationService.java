package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerMutationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Routes coop capture and release lifecycle changes through the shared owner/claim authority.
 * Ledger finalization is a continuation of the admitted mutation, never a precursor to it.
 */
final class CoopPopulationMutationService {
    private final CommandLinkedNpcCoopService coopService;
    private final CoopResidentSnapshotApplicationService snapshotApplicationService;

    CoopPopulationMutationService(@Nonnull CommandLinkedNpcCoopService coopService) {
        this.coopService = Objects.requireNonNull(coopService, "coopService");
        this.snapshotApplicationService = new CoopResidentSnapshotApplicationService();
    }

    boolean scheduleCapture(@Nonnull Ref<EntityStore> npcRef,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull UUID npcUuid,
                            @Nonnull String roleId,
                            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
                            @Nullable UUID ownerId,
                            @Nullable String ownerName,
                            @Nullable String[] toolIds,
                            @Nullable String displayName,
                            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                            @Nonnull CaptureCallbacks callbacks) {
        OwnerMutationScheduler scheduler = resolveScheduler();
        if (scheduler == null) {
            callbacks.onDenied("coop-capture-owner-authority-unavailable");
            return false;
        }
        CoopCaptureLedgerTransaction.CaptureRequest captureRequest =
                new CoopCaptureLedgerTransaction.CaptureRequest(
                        npcUuid,
                        roleId,
                        slotContext,
                        ownerId,
                        toolIds,
                        displayName,
                        snapshot
                );
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previousLedger =
                coopService.getLedgerSlotSnapshot(slotContext);
        return scheduler.scheduleWithDurableContext(
                npcRef,
                store,
                null,
                null,
                null,
                ownerId,
                ownerName,
                CompanionLifecycleState.COOP,
                OwnerPopulationOperation.LIFECYCLE_CHANGE,
                false,
                captureKey(npcUuid, slotContext),
                CoopPopulationLedgerMutationJson.capture(
                        coopService, captureRequest, previousLedger
                ),
                new OwnerMutationScheduler.MutationCallbacks() {
                    @Override
                    public boolean beforeApply(@Nonnull String profileId) {
                        return sameLedgerSource(
                                previousLedger,
                                coopService.getLedgerSlotSnapshot(slotContext)
                        );
                    }

                    @Override
                    public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                        callbacks.onDenied(reason);
                    }

                    @Override
                    public void onApplied(@Nonnull OwnerPopulationDecision decision,
                                          @Nonnull String profileId,
                                          @Nonnull OwnerMutationContext context) {
                        coopService.captureResidentInPopulationCommit(captureRequest);
                        callbacks.onCaptured(profileId, context);
                    }

                    @Override
                    public void onDurabilityDegraded(@Nonnull String reason) {
                        callbacks.onDurabilityDegraded(reason);
                    }
                }
        );
    }

    boolean scheduleRelease(@Nonnull Ref<EntityStore> targetRef,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull UUID currentNpcUuid,
                            @Nonnull String roleId,
                            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
                            boolean requireSnapshotOnRelease,
                            @Nonnull ReleaseCallbacks callbacks) {
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot ledger =
                coopService.getLedgerSlotSnapshot(slotContext);
        if (ledger == null) {
            callbacks.onDenied("slot_untracked");
            return false;
        }
        if (ledger.housedNpcUuid() == null) {
            if (currentNpcUuid.equals(ledger.lastReleasedNpcUuid())) {
                callbacks.onReconciled();
                return true;
            }
            callbacks.onDenied("release_without_capture");
            return false;
        }
        UUID previousNpcUuid = ledger.housedNpcUuid();
        CompanionIdentityResolver identityResolver = resolveIdentityResolver();
        OwnerMutationScheduler scheduler = resolveScheduler();
        String profileId = identityResolver == null
                ? null
                : identityResolver.resolveProfileId(previousNpcUuid).orElse(null);
        if (scheduler == null || profileId == null) {
            callbacks.onDenied(scheduler == null
                    ? "coop-release-owner-authority-unavailable"
                    : "coop-release-canonical-profile-unavailable");
            return false;
        }
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot previewSnapshot = ledger.stateSnapshot();
        UUID ownerId = resolveOwnerId(previewSnapshot, ledger.ownerId());
        String ownerName = previewSnapshot == null || previewSnapshot.owner() == null
                ? null
                : previewSnapshot.owner().getOwnerName();
        AtomicReference<CommandLinkedNpcCoopService.ReleaseResolution> appliedResolution =
                new AtomicReference<>();
        return scheduler.scheduleWithDurableContext(
                targetRef,
                store,
                profileId,
                previousNpcUuid,
                readOwnerId(targetRef, store),
                ownerId,
                ownerName,
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.RESTORE,
                false,
                releaseKey(previousNpcUuid, currentNpcUuid, slotContext),
                CoopPopulationLedgerMutationJson.release(
                        coopService, slotContext, ledger, currentNpcUuid
                ),
                new OwnerMutationScheduler.MutationCallbacks() {
                    @Override
                    public boolean beforeApply(@Nonnull String canonicalProfileId) {
                        CommandLinkedNpcCoopService.ReleaseResolution resolution = previewRelease(
                                previousNpcUuid, roleId, slotContext, requireSnapshotOnRelease
                        );
                        if (resolution.isFailure()) {
                            return false;
                        }
                        appliedResolution.set(resolution);
                        return true;
                    }

                    @Override
                    public void onApplyCompensated(@Nonnull String canonicalProfileId,
                                                   @Nonnull String reason) {
                        coopService.restoreLedgerSlotSnapshot(slotContext, ledger);
                        callbacks.onCompensated(reason);
                    }

                    @Override
                    public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                        callbacks.onDenied(reason);
                    }

                    @Override
                    public void onApplied(@Nonnull OwnerPopulationDecision decision,
                                          @Nonnull String canonicalProfileId,
                                          @Nonnull OwnerMutationContext context) {
                        CommandLinkedNpcCoopService.ReleaseResolution preview = appliedResolution.get();
                        if (preview == null) {
                            callbacks.onDurabilityDegraded("coop-release-preview-missing");
                            return;
                        }
                        CommandLinkedNpcCoopService.ReleaseResolution resolution =
                                coopService.resolveReleaseInPopulationCommit(
                                        currentNpcUuid,
                                        previousNpcUuid,
                                        roleId,
                                        slotContext,
                                        requireSnapshotOnRelease
                                );
                        if (resolution.isFailure()) {
                            callbacks.onDurabilityDegraded(
                                    "coop-release-ledger-commit-failed:" + resolution.failureReason()
                            );
                            return;
                        }
                        if (resolution.stateSnapshot() != null) {
                            snapshotApplicationService.applyDirect(
                                    context.npcRef(), context.store(), resolution.stateSnapshot()
                            );
                        } else {
                            snapshotApplicationService.applyLinkedFallbackDirect(
                                    context.npcRef(), context.store(), resolution.linkedSnapshot()
                            );
                        }
                        callbacks.onReleased(resolution, canonicalProfileId, context);
                    }

                    @Override
                    public void onDurabilityDegraded(@Nonnull String reason) {
                        callbacks.onDurabilityDegraded(reason);
                    }
                }
        );
    }

    @Nonnull
    private CommandLinkedNpcCoopService.ReleaseResolution previewRelease(
            @Nonnull UUID expectedHousedNpcUuid,
            @Nonnull String roleId,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
            boolean requireSnapshotOnRelease
    ) {
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot current =
                coopService.getLedgerSlotSnapshot(slotContext);
        if (current == null || !expectedHousedNpcUuid.equals(current.housedNpcUuid())) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure(
                    "source_resident_changed"
            );
        }
        if (current.roleId() != null && !current.roleId().equals(roleId)) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("role_mismatch");
        }
        if (requireSnapshotOnRelease && current.stateSnapshot() == null) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("snapshot_missing");
        }
        return new CommandLinkedNpcCoopService.ReleaseResolution(
                expectedHousedNpcUuid,
                current.stateSnapshot(),
                coopService.getCoopSnapshot(expectedHousedNpcUuid),
                false,
                null
        );
    }

    private static boolean sameLedgerSource(
            @Nullable CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot expected,
            @Nullable CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot current
    ) {
        if (expected == null || current == null) {
            return expected == current;
        }
        return Objects.equals(expected.housedNpcUuid(), current.housedNpcUuid())
                && Objects.equals(expected.lastReleasedNpcUuid(), current.lastReleasedNpcUuid());
    }

    @Nullable
    private static OwnerMutationScheduler resolveScheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }

    @Nullable
    private static CompanionIdentityResolver resolveIdentityResolver() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getCompanionIdentityResolver();
    }

    @Nullable
    private static UUID readOwnerId(@Nonnull Ref<EntityStore> reference,
                                    @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = type == null ? null : store.getComponent(reference, type);
        return owner == null ? null : owner.getOwnerId();
    }

    @Nullable
    private static UUID resolveOwnerId(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            @Nullable UUID fallback
    ) {
        if (snapshot != null && snapshot.owner() != null && snapshot.owner().getOwnerId() != null) {
            return snapshot.owner().getOwnerId();
        }
        if (snapshot != null && snapshot.commandLinks() != null
                && snapshot.commandLinks().getOwnerId() != null) {
            return snapshot.commandLinks().getOwnerId();
        }
        return fallback;
    }

    @Nonnull
    private static String captureKey(
            @Nonnull UUID npcUuid,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context
    ) {
        return "coop-capture:" + npcUuid + ":" + slotIdentity(context);
    }

    @Nonnull
    private static String releaseKey(
            @Nonnull UUID previousNpcUuid,
            @Nonnull UUID currentNpcUuid,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context
    ) {
        return "coop-release:" + previousNpcUuid + ":" + currentNpcUuid + ":" + slotIdentity(context);
    }

    @Nonnull
    private static String slotIdentity(@Nonnull CommandLinkedNpcCoopService.CoopSlotContext context) {
        return String.valueOf(context.worldName()) + ":" + context.coopId()
                + ":" + context.x() + ":" + context.y() + ":" + context.z()
                + ":" + context.residentSlot();
    }

    interface CaptureCallbacks {
        default void onCaptured(@Nonnull String profileId) {
        }

        default void onCaptured(@Nonnull String profileId, @Nonnull OwnerMutationContext context) {
            onCaptured(profileId);
        }

        void onDenied(@Nonnull String reason);

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }

    interface ReleaseCallbacks {
        void onReleased(@Nonnull CommandLinkedNpcCoopService.ReleaseResolution resolution,
                        @Nonnull String profileId);

        default void onReleased(@Nonnull CommandLinkedNpcCoopService.ReleaseResolution resolution,
                                @Nonnull String profileId,
                                @Nonnull OwnerMutationContext context) {
            onReleased(resolution, profileId);
        }

        void onDenied(@Nonnull String reason);

        default void onReconciled() {
        }

        default void onCompensated(@Nonnull String reason) {
        }

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }

}
