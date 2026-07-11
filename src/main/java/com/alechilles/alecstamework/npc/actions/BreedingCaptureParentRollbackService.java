package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restores one live parent and clears only presentation state still owned by the cancelled job. */
final class BreedingCaptureParentRollbackService
        implements BreedingCaptureCancellationService.ParentRollbackGateway {
    private static final String PAIR_HOOK_ID = "Tamework.Breeding.Pair.Start";
    private static final String PAIR_STATE = "BreedPair";
    private static final String IDLE_STATE = "Idle";
    private static final String LOCKED_TARGET_SLOT = "LockedTarget";

    private final BreedingParentStateService parentStateService = new BreedingParentStateService();

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public BreedingCaptureCancellationService.ParentRollbackOutcome rollback(
            @Nonnull Object storeScope,
            @Nonnull BreedingBirthJob job,
            boolean firstParent,
            @Nonnull UUID liveEntityUuid) {
        if (!(storeScope instanceof Store<?> rawStore)) {
            return outcome(BreedingCaptureCancellationService.ParentRollbackStatus.ERROR, false);
        }
        Store<EntityStore> store = (Store<EntityStore>) rawStore;
        store.assertThread();
        BreedingParentIdentity jobIdentity = firstParent ? job.firstParent() : job.secondParent();
        BreedingParentIdentity identity = new BreedingParentIdentity(
                liveEntityUuid,
                jobIdentity.profileId()
        );
        BreedingParentIdentity partner = firstParent ? job.secondParent() : job.firstParent();
        ParentBreedingSnapshot snapshot = firstParent
                ? job.firstParentSnapshot()
                : job.secondParentSnapshot();
        AppliedCooldownFingerprint fingerprint = firstParent
                ? job.firstParentCooldownFingerprint()
                : job.secondParentCooldownFingerprint();
        if (!fingerprint.applied()) {
            return outcome(
                    BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_NO_PROVISIONAL_STATE,
                    false
            );
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        Ref<EntityStore> ref = world != null ? world.getEntityRef(identity.entityUuid()) : null;
        if (world == null || ref == null || !ref.isValid() || isDead(ref, store)) {
            return outcome(
                    BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_PARENT_MISSING,
                    false
            );
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        TameworkBreedingComponent breeding = breeding(ref, store);
        if (npc == null || breeding == null) {
            return outcome(
                    BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_PARENT_MISSING,
                    false
            );
        }
        if (!parentStateService.matchesIdentity(identity, ref, npc, store)) {
            return outcome(
                    BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_IDENTITY_MISMATCH,
                    false
            );
        }
        if (!parentStateService.matchesFingerprint(fingerprint, breeding, npc)) {
            return outcome(
                    BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_NEWER_STATE,
                    false
            );
        }

        boolean restored = restore(identity, snapshot, fingerprint, ref, npc, breeding, store);
        boolean presentationCleared = clearPairingPresentation(ref, npc, partner, world, store);
        return outcome(
                restored
                        ? BreedingCaptureCancellationService.ParentRollbackStatus.RESTORED
                        : BreedingCaptureCancellationService.ParentRollbackStatus.SKIPPED_RESTORE_FAILED,
                presentationCleared
        );
    }

    private boolean restore(BreedingParentIdentity identity,
                            ParentBreedingSnapshot snapshot,
                            AppliedCooldownFingerprint fingerprint,
                            Ref<EntityStore> ref,
                            NPCEntity npc,
                            TameworkBreedingComponent breeding,
                            Store<EntityStore> store) {
        try {
            return parentStateService.restoreIfFingerprintMatches(
                    identity,
                    snapshot,
                    fingerprint,
                    ref,
                    npc,
                    breeding,
                    store
            );
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean clearPairingPresentation(Ref<EntityStore> ref,
                                             NPCEntity npc,
                                             BreedingParentIdentity partner,
                                             World world,
                                             Store<EntityStore> store) {
        Role role = npc.getRole();
        boolean pairStateActive = isPairStateActive(role);
        boolean hookCleared = clearPairHook(ref, store);
        boolean ownedPresentation = hookCleared || pairStateActive;
        boolean targetCleared = ownedPresentation && clearLockedPartner(role, partner, world);
        boolean stateCleared = pairStateActive && clearPairState(ref, role, store);
        boolean steeringCleared = ownedPresentation && clearPairSteering(role);
        return hookCleared || targetCleared || stateCleared || steeringCleared;
    }

    private boolean clearPairHook(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHookComponent> hookType = TameworkHookComponent.getComponentType();
        if (hookType == null) {
            return false;
        }
        try {
            TameworkHookComponent hook = store.getComponent(ref, hookType);
            if (hook == null || !hook.matchesHook(PAIR_HOOK_ID)) {
                return false;
            }
            store.tryRemoveComponent(ref, hookType);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean clearLockedPartner(@Nullable Role role,
                                       BreedingParentIdentity partner,
                                       World world) {
        MarkedEntitySupport marked = role != null ? role.getMarkedEntitySupport() : null;
        Ref<EntityStore> partnerRef = world.getEntityRef(partner.entityUuid());
        if (marked == null || partnerRef == null || !partnerRef.isValid()) {
            return false;
        }
        try {
            Ref<EntityStore> current = marked.getMarkedEntityRef(LOCKED_TARGET_SLOT);
            if (!Objects.equals(current, partnerRef)) {
                return false;
            }
            int slotCount = marked.getMarkedEntitySlotCount();
            for (int slot = 0; slot < slotCount; slot++) {
                if (LOCKED_TARGET_SLOT.equals(marked.getSlotName(slot))) {
                    marked.clearMarkedEntity(slot);
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            return false;
        }
        return false;
    }

    private boolean clearPairState(Ref<EntityStore> ref,
                                   @Nullable Role role,
                                   Store<EntityStore> store) {
        StateSupport state = role != null ? role.getStateSupport() : null;
        if (state == null || !isPairStateActive(role)) {
            return false;
        }
        try {
            state.setState(ref, IDLE_STATE, "", store);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isPairStateActive(@Nullable Role role) {
        StateSupport state = role != null ? role.getStateSupport() : null;
        return state != null && PAIR_STATE.equalsIgnoreCase(state.getStateName());
    }

    private boolean clearPairSteering(@Nullable Role role) {
        if (role == null) {
            return false;
        }
        try {
            boolean cleared = false;
            if (role.getBodySteering() != null) {
                role.getBodySteering().clear();
                cleared = true;
            }
            if (role.getHeadSteering() != null) {
                role.getHeadSteering().clear();
                cleared = true;
            }
            return cleared;
        } catch (RuntimeException ignored) {
            // The durable cancellation already won; presentation cleanup remains best effort.
            return false;
        }
    }

    @Nullable
    private TameworkBreedingComponent breeding(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type =
                TameworkBreedingComponent.getComponentType();
        return type != null ? store.getComponent(ref, type) : null;
    }

    private boolean isDead(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, DeathComponent> type = DeathComponent.getComponentType();
        return type != null && store.getComponent(ref, type) != null;
    }

    @Nonnull
    private BreedingCaptureCancellationService.ParentRollbackOutcome outcome(
            BreedingCaptureCancellationService.ParentRollbackStatus status,
            boolean pairingStateCleared) {
        return new BreedingCaptureCancellationService.ParentRollbackOutcome(
                status,
                pairingStateCleared
        );
    }
}
