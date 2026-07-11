package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Applies provisional cooldown and approach movement after a job reservation is registered. */
final class BreedingPairEffectsService {
    private static final String PAIR_HOOK_ID = "Tamework.Breeding.Pair.Start";
    private static final String PAIR_STATE = "BreedPair";
    private static final String LOCKED_TARGET_SLOT = "LockedTarget";
    private static final double APPROACH_SPACING = 0.45;

    private final BreedingCooldownService cooldownService = new BreedingCooldownService();
    private final BreedingParentStateService parentStateService = new BreedingParentStateService();

    boolean apply(@Nonnull EffectContext context) {
        cooldownService.applyParentCooldown(
                context.sourceRef(), context.sourceBreeding(), context.sourceNpc(),
                context.partnerNpc().getUuid(), context.sourceCooldown().durationMs(), context.nowMs(),
                context.happinessUpdatedAtMs(), context.store(), context.commandBuffer()
        );
        cooldownService.applyParentCooldown(
                context.partnerRef(), context.partnerBreeding(), context.partnerNpc(),
                context.sourceNpc().getUuid(), context.partnerCooldown().durationMs(), context.nowMs(),
                context.happinessUpdatedAtMs(), context.store(), context.commandBuffer()
        );
        moveParents(context);
        logCooldown(context.sourceNpc(), context.sourceOwner(), context.sourceCooldown());
        logCooldown(context.partnerNpc(), context.partnerOwner(), context.partnerCooldown());
        return true;
    }

    void rollback(@Nonnull EffectContext context, @Nonnull BreedingBirthJob job) {
        rollbackParent(
                context.sourceRef(), context.sourceNpc(), context.sourceBreeding(), context, job
        );
        rollbackParent(
                context.partnerRef(), context.partnerNpc(), context.partnerBreeding(), context, job
        );
    }

    private void rollbackParent(Ref<EntityStore> ref,
                                NPCEntity npc,
                                TameworkBreedingComponent breeding,
                                EffectContext context,
                                BreedingBirthJob job) {
        BreedingParentIdentity identity;
        ParentBreedingSnapshot snapshot;
        AppliedCooldownFingerprint fingerprint;
        if (job.firstParent().entityUuid().equals(npc.getUuid())) {
            identity = job.firstParent();
            snapshot = job.firstParentSnapshot();
            fingerprint = job.firstParentCooldownFingerprint();
        } else if (job.secondParent().entityUuid().equals(npc.getUuid())) {
            identity = job.secondParent();
            snapshot = job.secondParentSnapshot();
            fingerprint = job.secondParentCooldownFingerprint();
        } else {
            return;
        }
        parentStateService.restoreIfFingerprintMatches(
                identity,
                snapshot,
                fingerprint,
                ref,
                npc,
                breeding,
                context.store(),
                context.commandBuffer()
        );
    }

    private void moveParents(EffectContext context) {
        TransformComponent sourceTransform = transform(context.sourceRef(), context.store());
        TransformComponent partnerTransform = transform(context.partnerRef(), context.store());
        if (sourceTransform == null || partnerTransform == null) {
            return;
        }
        PairingTargets targets = pairingTargets(sourceTransform, partnerTransform);
        setLookTarget(context.sourceNpc(), context.partnerRef());
        setLookTarget(context.partnerNpc(), context.sourceRef());
        moveParent(context.sourceNpc(), context.sourceRef(), targets.source(), context);
        moveParent(context.partnerNpc(), context.partnerRef(), targets.partner(), context);
    }

    private void setLookTarget(NPCEntity npc, Ref<EntityStore> target) {
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            role.getMarkedEntitySupport().setMarkedEntity(LOCKED_TARGET_SLOT, target);
        }
    }

    private void moveParent(NPCEntity npc,
                            Ref<EntityStore> ref,
                            Vector3d target,
                            EffectContext context) {
        if (!applyPairHook(npc, ref, target, context)) {
            npc.moveTo(ref, target.x, target.y, target.z, context.store());
        }
    }

    private boolean applyPairHook(NPCEntity npc,
                                  Ref<EntityStore> ref,
                                  Vector3d target,
                                  EffectContext context) {
        Role role = npc.getRole();
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null
                || role.getStateSupport().getStateHelper().getStateIndex(PAIR_STATE) == StateSupport.NO_STATE) {
            return false;
        }
        ComponentType<EntityStore, TameworkHookComponent> type = TameworkHookComponent.getComponentType();
        if (type == null) {
            return false;
        }
        putComponent(ref, context.store(), context.commandBuffer(), type, new TameworkHookComponent(
                PAIR_HOOK_ID, null, null, null, System.currentTimeMillis(), true, target
        ));
        return true;
    }

    private PairingTargets pairingTargets(TransformComponent source, TransformComponent partner) {
        Vector3d a = source.getPosition();
        Vector3d b = partner.getPosition();
        double y = Math.max(a.y, b.y);
        Vector3d midpoint = new Vector3d((a.x + b.x) * 0.5, y, (a.z + b.z) * 0.5);
        Vector3d axis = new Vector3d(b).sub(a);
        if (axis.lengthSquared() <= 0.00001) {
            return new PairingTargets(
                    new Vector3d(midpoint.x - APPROACH_SPACING, y, midpoint.z),
                    new Vector3d(midpoint.x + APPROACH_SPACING, y, midpoint.z)
            );
        }
        axis.normalize();
        return new PairingTargets(
                new Vector3d(midpoint.x - axis.x * APPROACH_SPACING, y, midpoint.z - axis.z * APPROACH_SPACING),
                new Vector3d(midpoint.x + axis.x * APPROACH_SPACING, y, midpoint.z + axis.z * APPROACH_SPACING)
        );
    }

    @Nullable
    private TransformComponent transform(Ref<EntityStore> ref, Store<EntityStore> store) {
        return ref != null && ref.isValid()
                ? store.getComponent(ref, TransformComponent.getComponentType())
                : null;
    }

    private <T extends Component<EntityStore>> void putComponent(
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer,
            ComponentType<EntityStore, T> type,
            T value) {
        if (commandBuffer != null) {
            commandBuffer.putComponent(ref, type, value);
        } else {
            store.putComponent(ref, type, value);
        }
    }

    private void logCooldown(NPCEntity npc,
                             BreedingOffspringProgressionService.OwnerSnapshot owner,
                             BreedingParentCooldownResolver.ResolvedCooldown cooldown) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null || !plugin.isDebugBreedingEnabled()) {
            return;
        }
        plugin.getLogger().at(Level.INFO).log(String.format(
                "Breeding cooldown applied: npc=%s owner=%s basis=%s base=%ds random=%ds traitMult=%.3f gameMs=%d.",
                npc.getUuid(), owner.ownerId(), cooldown.basis(), cooldown.baseSeconds(),
                cooldown.randomDelaySeconds(), cooldown.traitMultiplier(), cooldown.durationMs()
        ));
    }

    record EffectContext(
            Ref<EntityStore> sourceRef,
            NPCEntity sourceNpc,
            TameworkBreedingComponent sourceBreeding,
            Ref<EntityStore> partnerRef,
            NPCEntity partnerNpc,
            TameworkBreedingComponent partnerBreeding,
            BreedingParentCooldownResolver.ResolvedCooldown sourceCooldown,
            BreedingParentCooldownResolver.ResolvedCooldown partnerCooldown,
            BreedingOffspringProgressionService.OwnerSnapshot sourceOwner,
            BreedingOffspringProgressionService.OwnerSnapshot partnerOwner,
            long nowMs,
            long happinessUpdatedAtMs,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer) {
    }

    private record PairingTargets(Vector3d source, Vector3d partner) {
    }
}
