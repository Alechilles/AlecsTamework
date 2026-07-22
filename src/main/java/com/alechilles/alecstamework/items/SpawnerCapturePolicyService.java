package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Evaluates whether a spawner item is currently allowed to capture a target NPC.
 */
public final class SpawnerCapturePolicyService {
    private final HytaleLogger logger;
    private final SpawnerRolePolicyService rolePolicyService;
    private final SpawnerNpcStateService npcStateService;
    private final SpawnerOwnershipPolicyService ownershipPolicyService;
    private final SpawnerNpcIdentityService npcIdentityService;

    public SpawnerCapturePolicyService(HytaleLogger logger,
                                       SpawnerRolePolicyService rolePolicyService,
                                       SpawnerNpcStateService npcStateService,
                                       SpawnerOwnershipPolicyService ownershipPolicyService,
                                       SpawnerNpcIdentityService npcIdentityService) {
        this.logger = logger;
        this.rolePolicyService = rolePolicyService;
        this.npcStateService = npcStateService;
        this.ownershipPolicyService = ownershipPolicyService;
        this.npcIdentityService = npcIdentityService;
    }

    public boolean canCapture(Player player, Ref<EntityStore> targetRef, ItemFeatureConfig config, ItemStack itemStack) {
        return canCapture(player, targetRef, config, itemStack, true);
    }

    public boolean canBeginCaptureChannel(Player player,
                                          Ref<EntityStore> targetRef,
                                          ItemFeatureConfig config,
                                          ItemStack itemStack) {
        return canCapture(player, targetRef, config, itemStack, false);
    }

    private boolean canCapture(Player player,
                               Ref<EntityStore> targetRef,
                               ItemFeatureConfig config,
                               ItemStack itemStack,
                               boolean enforceTerminalRequirements) {
        if (player == null || targetRef == null || config == null || itemStack == null) {
            logCaptureDebug("denied reason=invalid-input player=" + (player != null ? player.getUuid() : null));
            return false;
        }
        if (!targetRef.isValid()) {
            logCaptureDebug("denied reason=invalid-target-ref player=" + player.getUuid());
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs())) {
            logCaptureDebug("denied reason=cooldown item=" + itemStack.getItemId() + " player=" + player.getUuid());
            return false;
        }
        World world = player.getWorld();
        if (world == null) {
            logCaptureDebug("denied reason=missing-world player=" + player.getUuid());
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null) {
            logCaptureDebug("denied reason=target-not-npc player=" + player.getUuid());
            return false;
        }
        String roleId = rolePolicyService.resolveRoleIdFromNpc(npc);
        if (!rolePolicyService.isRoleAllowed(roleId, config)) {
            logCaptureDebug("denied reason=role-not-allowed player=" + player.getUuid() + " role=" + roleId);
            return false;
        }
        if (config.isCaptureRequireTamed() && !npcStateService.resolveTamedState(targetRef, world)) {
            String npcName = npcIdentityService.resolveDisplayName(npc);
            OwnerMessageUtil.sendUntamed(player, npcName);
            logCaptureDebug("denied reason=not-tamed player=" + player.getUuid() + " role=" + roleId);
            return false;
        }
        UUID ownerUuid = npcStateService.resolveOwnerFromComponent(targetRef, world);
        if (config.isCaptureTamesTarget()) {
            if (npcStateService.resolveTamedState(targetRef, world) || ownerUuid != null) {
                logCaptureDebug("denied reason=wild-capture-target-not-wild player=" + player.getUuid() + " role=" + roleId);
                return false;
            }
            if (config.resolveCaptureTamedRole(roleId) == null) {
                logCaptureDebug("denied reason=missing-tamed-role-mapping player=" + player.getUuid() + " role=" + roleId);
                return false;
            }
        }
        if (enforceTerminalRequirements && !meetsHealthRequirement(targetRef, config, store)) {
            CaptureHealth health = resolveCaptureHealth(targetRef, store);
            String currentPercent = health == null ? "unavailable"
                    : Double.toString((health.currentHealth() / health.maximumHealth()) * 100.0D);
            logCaptureDebug("denied reason=health-threshold player=" + player.getUuid()
                    + " currentHealthPercent=" + currentPercent
                    + " maxHealthPercent=" + config.getCaptureMaxHealthPercent());
            return false;
        }
        if (enforceTerminalRequirements && !hasRequiredEffect(targetRef, config, store)) {
            logCaptureDebug("denied reason=required-effect player=" + player.getUuid()
                    + " effect=" + config.getCaptureRequiredEffectId());
            return false;
        }
        if (!ownershipPolicyService.isCaptureAllowed(player.getUuid(), ownerUuid, config)) {
            logCaptureDebug(
                    "denied reason=ownership-policy player=" + player.getUuid()
                            + " owner=" + ownerUuid
                            + " requireOwnerOverride=" + config.getCaptureRequireOwnerOverride()
                            + " ownerRestricted=" + config.isCaptureOwnerRestricted()
            );
            if (ownerUuid != null) {
                String npcName = npcIdentityService.resolveDisplayName(npc);
                String ownerName = npcStateService.resolveOwnerNameFromComponent(targetRef, world);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "capture");
            }
            return false;
        }
        boolean inDistance = isWithinCaptureDistance(player, targetRef, config, store);
        if (!inDistance) {
            logCaptureDebug("denied reason=distance player=" + player.getUuid() + " maxDistance=" + config.getCaptureMaxDistance());
        }
        return inDistance;
    }

    private boolean meetsHealthRequirement(Ref<EntityStore> targetRef,
                                           ItemFeatureConfig config,
                                           Store<EntityStore> store) {
        Double maximumPercent = config.getCaptureMaxHealthPercent();
        if (maximumPercent == null) {
            return true;
        }
        if (!Double.isFinite(maximumPercent) || maximumPercent < 0.0 || maximumPercent > 100.0) {
            return false;
        }
        CaptureHealth health = resolveCaptureHealth(targetRef, store);
        if (health == null) return false;
        double currentPercent = Math.max(0.0, Math.min(100.0,
                (health.currentHealth() / health.maximumHealth()) * 100.0));
        return currentPercent <= maximumPercent;
    }

    /** Samples finite terminal health evidence for the durable chance boundary. */
    public CaptureHealth resolveCaptureHealth(Ref<EntityStore> targetRef, Store<EntityStore> store) {
        var statType = EntityStatMap.getComponentType();
        if (targetRef == null || store == null || statType == null || EntityStatType.getAssetMap() == null) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(targetRef, statType);
        if (statMap == null) return null;
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) return null;
        EntityStatValue health = statMap.get(healthIndex);
        if (health == null || !Double.isFinite(health.get()) || !Double.isFinite(health.getMax())
                || health.getMax() <= 0.0 || health.get() < 0.0 || health.get() > health.getMax()) {
            return null;
        }
        return new CaptureHealth(health.get(), health.getMax());
    }

    public record CaptureHealth(double currentHealth, double maximumHealth) { }

    private boolean hasRequiredEffect(Ref<EntityStore> targetRef,
                                      ItemFeatureConfig config,
                                      Store<EntityStore> store) {
        String requiredEffectId = config.getCaptureRequiredEffectId();
        if (requiredEffectId == null || requiredEffectId.isBlank()) {
            return true;
        }
        var effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return false;
        }
        EffectControllerComponent effectController = store.getComponent(targetRef, effectType);
        return TameworkEntityEffectService.hasActiveEffect(effectController, requiredEffectId);
    }

    private void logCaptureDebug(String message) {
        // Capture interactions previously failed silently unless the global spawner-debug flag
        // happened to be enabled. One bounded line per evaluated interaction is operationally
        // useful and makes terminal channel failures diagnosable from ordinary server logs.
        logger.at(Level.INFO).log("Spawner capture eligibility: " + message);
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

