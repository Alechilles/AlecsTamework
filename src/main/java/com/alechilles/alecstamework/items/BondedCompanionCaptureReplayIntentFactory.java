package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Freezes stable live identity, then rebuilds replay from committed evidence.
 *
 * <p>The exact retry deliberately uses the durable capture snapshot instead of
 * rereading a mutable live NPC after the original commit.</p>
 */
final class BondedCompanionCaptureReplayIntentFactory {
    private static final String CALLER_NAMESPACE =
            "spawner-bonded-capture:v1";

    private final SpawnerRolePolicyService roles;

    BondedCompanionCaptureReplayIntentFactory(
            SpawnerRolePolicyService roles
    ) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    /** Captures only stable source identity needed to find durable evidence. */
    @Nullable
    BondedCompanionCaptureReplayGateway.Request request(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        if (player == null || targetRef == null || !targetRef.isValid()
                || source == null || source.isEmpty() || config == null
                || store == null) {
            return null;
        }
        UUIDComponent identity = store.getComponent(
                targetRef, UUIDComponent.getComponentType());
        NPCEntity npc = store.getComponent(
                targetRef, NPCEntity.getComponentType());
        UUID sourceUuid = identity == null ? null : identity.getUuid();
        String sourceRoleId = roles.resolveRoleIdFromNpc(npc);
        String roleId = BondedCompanionCaptureRoleResolver.authoritativeRole(
                config, sourceRoleId);
        String rosterId = config.getCaptureMechanics().bondedRosterId();
        if (sourceUuid == null || roleId == null || roleId.isBlank()
                || rosterId == null || rosterId.isBlank()
                || source.getItemId() == null
                || source.getItemId().isBlank()) {
            return null;
        }
        return new BondedCompanionCaptureReplayGateway.Request(
                CALLER_NAMESPACE,
                player.getUuid() + ":" + rosterId + ":" + sourceUuid,
                player.getUuid(), rosterId, sourceUuid, world.getName(),
                source.getItemId(), roleId);
    }

    /** Reconstructs the exact committed request without current policy state. */
    BondedCompanionCaptureIntent intent(
            BondedCompanionCaptureReplayGateway.Request request,
            CaptureAttemptHandle attempt,
            BondedCompanionCaptureReplayGateway.Evidence evidence
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(evidence, "evidence");
        return new BondedCompanionCaptureIntent(
                request.callerNamespace(), request.idempotencyKey(),
                request.actorUuid(), evidence.sourceWorldKey(),
                attempt.hotbarSlot(), attempt.sourceFingerprint(),
                request.sourceNpcUuid(), evidence.attemptEvidence(),
                evidence.roleId(), null, request.rosterId(), 0L,
                evidence.snapshot(), null, true, true, true, true, true, true,
                evidence.familyId(),
                BondedCompanionCaptureIntent.FamilySelection.ROLE_INFERRED);
    }
}
