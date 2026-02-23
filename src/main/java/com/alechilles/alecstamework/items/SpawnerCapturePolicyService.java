package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;

/**
 * Evaluates whether a spawner item is currently allowed to capture a target NPC.
 */
public final class SpawnerCapturePolicyService {
    private final SpawnerRolePolicyService rolePolicyService;
    private final SpawnerNpcStateService npcStateService;
    private final SpawnerOwnershipPolicyService ownershipPolicyService;
    private final SpawnerNpcIdentityService npcIdentityService;

    public SpawnerCapturePolicyService(SpawnerRolePolicyService rolePolicyService,
                                       SpawnerNpcStateService npcStateService,
                                       SpawnerOwnershipPolicyService ownershipPolicyService,
                                       SpawnerNpcIdentityService npcIdentityService) {
        this.rolePolicyService = rolePolicyService;
        this.npcStateService = npcStateService;
        this.ownershipPolicyService = ownershipPolicyService;
        this.npcIdentityService = npcIdentityService;
    }

    public boolean canCapture(Player player, Ref<EntityStore> targetRef, ItemFeatureConfig config, ItemStack itemStack) {
        if (player == null || targetRef == null || config == null || itemStack == null) {
            return false;
        }
        if (!targetRef.isValid()) {
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs())) {
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        String roleId = rolePolicyService.resolveRoleIdFromNpc(npc);
        if (!rolePolicyService.isRoleAllowed(roleId, config)) {
            return false;
        }
        if (config.isCaptureRequireTamed() && !npcStateService.resolveTamedState(targetRef, world)) {
            String npcName = npcIdentityService.resolveDisplayName(npc);
            OwnerMessageUtil.sendUntamed(player, npcName);
            return false;
        }
        UUID ownerUuid = npcStateService.resolveOwnerFromComponent(targetRef, world);
        if (!ownershipPolicyService.isCaptureAllowed(player.getUuid(), ownerUuid, config)) {
            if (ownerUuid != null) {
                String npcName = npcIdentityService.resolveDisplayName(npc);
                String ownerName = npcStateService.resolveOwnerNameFromComponent(targetRef, world);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "capture");
            }
            return false;
        }
        return isWithinCaptureDistance(player, targetRef, config, store);
    }

    private boolean isWithinCaptureDistance(Player player,
                                            Ref<EntityStore> targetRef,
                                            ItemFeatureConfig config,
                                            Store<EntityStore> store) {
        if (player == null || targetRef == null || config == null || store == null) {
            return false;
        }
        double maxDistance = config.getCaptureMaxDistance();
        if (maxDistance <= 0) {
            return true;
        }
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (playerTransform == null || targetTransform == null) {
            return false;
        }
        Vector3d p = new Vector3d(playerTransform.getPosition());
        Vector3d t = new Vector3d(targetTransform.getPosition());
        double dx = p.x - t.x;
        double dy = p.y - t.y;
        double dz = p.z - t.z;
        double maxDistSq = maxDistance * maxDistance;
        return (dx * dx + dy * dy + dz * dz) <= maxDistSq;
    }

    private boolean isCooldownActive(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return false;
        }
        Long until = itemStack.getFromMetadataOrNull(key, Codec.LONG);
        if (until == null) {
            return false;
        }
        return until > System.currentTimeMillis();
    }
}

