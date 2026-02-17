package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import it.unimi.dsi.fastutil.Pair;
import org.bson.BsonDocument;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Capture/spawn logic for spawner items, including metadata and attachments.
 */
public final class SpawnerFeatureHandler {

    private static final double SPAWN_OFFSET_Y = 0.5;
    private static final double SPAWN_SURFACE_OFFSET_Y = 0.01;
    private static final double SPAWN_FORWARD_DISTANCE = 1.5;
    private static final double RAYCAST_DISTANCE_EPSILON = 0.1;
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final Gson GSON = new Gson();
    private static final Type ATTACHMENT_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private static final class CapturedName {
        private final String name;
        private final UUID ownerId;
        private final long updatedMs;
        private final TameworkNpcNameComponent.NameSource source;

        private CapturedName(String name, UUID ownerId, long updatedMs, TameworkNpcNameComponent.NameSource source) {
            this.name = name;
            this.ownerId = ownerId;
            this.updatedMs = updatedMs;
            this.source = source;
        }
    }

    private static final class CaptureInfo {
        private final String attachmentsJson;
        private final Integer roleIndex;
        private final String npcNameKey;
        private final String iconPath;
        private final CapturedName capturedName;

        private CaptureInfo(String attachmentsJson,
                            Integer roleIndex,
                            String npcNameKey,
                            String iconPath,
                            CapturedName capturedName) {
            this.attachmentsJson = attachmentsJson;
            this.roleIndex = roleIndex;
            this.npcNameKey = npcNameKey;
            this.iconPath = iconPath;
            this.capturedName = capturedName;
        }
    }


    private final HytaleLogger logger;
    private final ItemFeatureRegistry registry;

    public SpawnerFeatureHandler(HytaleLogger logger, ItemFeatureRegistry registry) {
        this.logger = logger;
        this.registry = registry;
    }

    // Entry point for in-world item interaction; decides capture vs spawn.
    public boolean handle(PlayerInteractEvent event, ItemFeatureConfig config) {
        if (event == null) {
            return false;
        }
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }

        ItemStack itemStack = event.getItemInHand();
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }

        InteractionType action = event.getActionType();
        if (action != InteractionType.Primary && action != InteractionType.Use) {
            logger.at(Level.FINE).log(
                    "Spawner stub: ignoring action=" + action
                            + " item=" + itemStack.getItemId()
            );
            return false;
        }

        Entity targetEntity = event.getTargetEntity();
        if (targetEntity != null) {
            return captureStub(event.getPlayer(), itemStack, targetEntity, config);
        }

        return spawnFromItem(event.getPlayer(), itemStack, config, null, null);
    }

    // Entry point for packet-driven interactions; validates slot/item then forwards to capture/spawn.
    public void handlePacket(Player player,
                             String itemId,
                             int activeHotbarSlot,
                             int targetEntityId,
                             InteractionType interactionType,
                             ItemFeatureConfig config) {
        ItemFeatureConfig activeConfig = config;
        if (interactionType != InteractionType.Primary && interactionType != InteractionType.Use) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet ignored action=" + interactionType
                            + " item=" + itemId
            );
            return;
        }
        if (activeHotbarSlot < 0) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet missing hotbar slot for item=" + itemId
            );
            return;
        }
        if (player == null) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet missing player for item=" + itemId
                            + " slot=" + activeHotbarSlot
            );
            return;
        }

        ItemStack itemStack = getHotbarItem(player, activeHotbarSlot);
        if (itemStack == null || itemStack.isEmpty()) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet empty slot item=" + itemId
                            + " slot=" + activeHotbarSlot
            );
            return;
        }
        if (!itemId.equals(itemStack.getItemId())) {
            logger.at(Level.FINE).log(
                    "Spawner stub: packet item mismatch itemId=" + itemId
                            + " slotItem=" + itemStack.getItemId()
                            + " slot=" + activeHotbarSlot
            );
            if (registry != null) {
                ItemFeatureConfig slotConfig = registry.get(itemStack.getItemId());
                if (slotConfig != null) {
                    activeConfig = slotConfig;
                    itemId = itemStack.getItemId();
                    logger.at(Level.FINE).log(
                            "Spawner stub: packet using slot item config item=" + itemId
                    );
                }
            }
        }
        if (activeConfig == null || !activeConfig.isSpawnerEnabled()) {
            return;
        }

        if (targetEntityId > 0) {
            captureStub(player, itemStack, targetEntityId, activeConfig, activeHotbarSlot);
            return;
        }

        spawnFromItem(player, itemStack, activeConfig, activeHotbarSlot, null);
    }

    private boolean captureStub(Player player, ItemStack itemStack, Entity targetEntity, ItemFeatureConfig config) {
        if (player == null || itemStack == null || config == null || targetEntity == null) {
            return false;
        }
        if (!(targetEntity instanceof NPCEntity)) {
            return false;
        }
        Ref<EntityStore> targetRef = ((NPCEntity) targetEntity).getReference();
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        return captureFromNpcAction(player, targetRef, itemStack, config);
    }

    private void captureStub(Player player, ItemStack itemStack, int targetEntityId, ItemFeatureConfig config, int activeHotbarSlot) {
        if (player == null || itemStack == null || config == null) {
            return;
        }
        Ref<EntityStore> targetRef = resolveEntityRef(player, targetEntityId, null);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        captureFromNpcAction(player, targetRef, itemStack, config);
    }

    public boolean canCaptureInteraction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack) {
        if (player == null || targetRef == null || itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        if (isAlreadyCaptured(itemStack)) {
            return false;
        }
        return canCapture(player, targetRef, config, itemStack);
    }

    public boolean canSpawnInteraction(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        ItemFeatureConfig baseConfig = resolveConfigForItem(itemStack);
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(baseConfig, null);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())) {
            return false;
        }
        if (!isFilledItem(itemStack, config)) {
            return false;
        }
        String roleId = resolveSpawnRoleId(itemStack, config);
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return isRoleAllowed(roleId, config);
    }

    // Used by TameworkSpawnInteraction: capture from a targeted NPC using the held spawner item.
    public boolean captureFromItemInteraction(Player player, ItemStack itemStack, Ref<EntityStore> targetRef) {
        if (player == null || itemStack == null || itemStack.isEmpty() || targetRef == null) {
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        if (isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        return captureFromNpcAction(player, targetRef, itemStack, config);
    }

    // Used by TameworkSpawnInteraction: builds a minimal config to spawn from the held item.
    public boolean spawnFromItemInteraction(Player player,
                                            ItemStack itemStack,
                                            String emptyItemIdOverride,
                                            Boolean spawnAssignsOwnerOverride) {
        ItemFeatureConfig baseConfig = resolveConfigForItem(itemStack);
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(
                baseConfig,
                spawnAssignsOwnerOverride
        );
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        return spawnFromItem(player, itemStack, config, null, emptyItemIdOverride);
    }

    private boolean spawnFromItem(Player player, ItemStack itemStack, ItemFeatureConfig config, Integer hotbarSlot, String emptyItemIdOverride) {
        if (player == null || itemStack == null || config == null) {
            return false;
        }
        if (!config.isSpawnerEnabled()) {
            return false;
        }
        if (isCooldownActive(itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())) {
            return false;
        }
        if (!isFilledItem(itemStack, config)) {
            return false;
        }

        String roleId = resolveSpawnRoleId(itemStack, config);
        if (roleId == null || roleId.isBlank()) {
            logger.at(Level.FINE).log("Spawner spawn: missing role for item=" + itemStack.getItemId());
            return false;
        }
        if (!isRoleAllowed(roleId, config)) {
            logger.at(Level.FINE).log("Spawner spawn: role not allowed role=" + roleId);
            return false;
        }

        World world = player.getWorld();
        if (world == null) {
            return false;
        }

        Vector3d spawnPosition = resolveSpawnPosition(player, config);
        if (spawnPosition == null) {
            return false;
        }
        if (!isWithinSpawnDistance(player, spawnPosition, config)) {
            return false;
        }

        UUID ownerUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        if (!isSpawnAllowedByOwnership(player.getUuid(), ownerUuid, config)) {
            return false;
        }
        boolean tamed = Boolean.TRUE.equals(itemStack.getFromMetadataOrNull(TameworkMetadataKeys.TAMED, Codec.BOOLEAN));
        if (ownerUuid == null && config.isSpawnAssignsOwner()) {
            ownerUuid = player.getUuid();
        }

        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return false;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            logger.at(Level.FINE).log("Spawner spawn: unknown role=" + roleId);
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        Vector3f rotation = resolveSpawnRotation(store, playerRef, spawnPosition);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, spawnPosition, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            logger.at(Level.FINE).log("Spawner spawn: failed to spawn role=" + roleId);
            return false;
        }

        Ref<EntityStore> npcRef = spawned.first();
        NPCEntity npc = spawned.second();
        applyAttachments(itemStack, npcRef, npc, store);
        applyOwner(config, npcRef, npc, playerRef, ownerUuid, world);
        applyTamed(npcRef, tamed, world);
        applyCapturedName(itemStack, npcRef, store);

        ItemStack updated = itemStack;
        if (isAlreadyCaptured(itemStack)) {
            String emptyItemId = emptyItemIdOverride != null ? emptyItemIdOverride : resolveEmptyItemId(itemStack.getItemId());
            if (emptyItemId != null && !emptyItemId.isBlank()) {
                updated = swapItemId(updated, emptyItemId);
            }
            updated = clearCapturedMetadata(updated);
        }
        updated = applyCooldown(updated, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs());

        boolean updatedOk = hotbarSlot != null
                ? updateHotbarSlot(player, hotbarSlot, updated)
                : updateHeldItem(player, updated);
        if (!updatedOk) {
            logger.at(Level.WARNING).log("Spawner spawn: failed to update held item.");
            return false;
        }

        spawnSpawnParticles(world, npcRef, config);
        return true;
    }

    private ItemFeatureConfig resolveConfigForItem(ItemStack itemStack) {
        if (registry == null || itemStack == null) {
            return null;
        }
        String itemId = itemStack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ItemFeatureConfig config = registry.get(itemId);
        if (config != null) {
            return config;
        }
        String emptyItemId = resolveEmptyItemId(itemId);
        if (emptyItemId != null && !emptyItemId.isBlank()) {
            return registry.get(emptyItemId);
        }
        return null;
    }


    private ItemFeatureConfig buildSpawnerConfigForInteraction(ItemFeatureConfig baseConfig,
                                                               Boolean spawnAssignsOwnerOverride) {
        if (baseConfig == null) {
            return null;
        }
        boolean spawnAssignsOwner = spawnAssignsOwnerOverride != null
                ? spawnAssignsOwnerOverride
                : baseConfig.isSpawnAssignsOwner();
        return ItemFeatureConfig.builder()
                .spawnerEnabled(baseConfig.isSpawnerEnabled())
                .whistleEnabled(baseConfig.isWhistleEnabled())
                .captureClearsOwner(baseConfig.isCaptureClearsOwner())
                .captureRequireTamed(baseConfig.isCaptureRequireTamed())
                .captureOwnerRestricted(baseConfig.isCaptureOwnerRestricted())
                .spawnAssignsOwner(spawnAssignsOwner)
                .spawnOwnerRestricted(baseConfig.isSpawnOwnerRestricted())
                .whistleRadius(baseConfig.getWhistleRadius())
                .spawnerRoleAllowlist(baseConfig.getSpawnerRoleAllowlist())
                .spawnerRoleDenylist(baseConfig.getSpawnerRoleDenylist())
                .spawnerRoleListMode(baseConfig.getSpawnerRoleListMode())
                                .captureRequireOwnerOverride(baseConfig.getCaptureRequireOwnerOverride())
                .spawnRequireOwnerOverride(baseConfig.getSpawnRequireOwnerOverride())
                .captureParticleSystem(baseConfig.getCaptureParticleSystem())
                .spawnParticleSystem(baseConfig.getSpawnParticleSystem())
                .captureSoundEvent(baseConfig.getCaptureSoundEvent())
                .spawnSoundEvent(baseConfig.getSpawnSoundEvent())
                .captureCooldownMs(baseConfig.getCaptureCooldownMs())
                .spawnCooldownMs(baseConfig.getSpawnCooldownMs())
                .captureMaxDistance(baseConfig.getCaptureMaxDistance())
                .spawnMaxDistance(baseConfig.getSpawnMaxDistance())
                .spawnerFilledItemId(baseConfig.getSpawnerFilledItemId())
                .spawnerIconDefault(baseConfig.getSpawnerIconDefault())
                .spawnerIconOverrides(baseConfig.getSpawnerIconOverrides())
                .spawnerIconOverridesByRole(baseConfig.getSpawnerIconOverridesByRole())
                .build();
    }

    private String resolveSpawnRoleId(ItemStack itemStack, ItemFeatureConfig config) {
        String roleId = resolveRoleIdFromMetadata(itemStack);
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return null;
    }

    private String resolveRoleIdFromMetadata(ItemStack itemStack) {
        CapturedNPCMetadata meta = resolveCapturedMetadata(itemStack);
        if (meta == null) {
            return null;
        }

        String roleId = readStringGetter(meta,
                "getRoleId",
                "getRoleKey",
                "getRoleNameKey",
                "getRoleName",
                "getNpcNameKey"
        );
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }

        Integer roleIndex = readIntGetter(meta, "getRoleIndex");
        if (roleIndex != null && roleIndex >= 0) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                String roleName = npcPlugin.getName(roleIndex);
                if (roleName != null && !roleName.isBlank()) {
                    return roleName;
                }
            }
        }
        return null;
    }

    private CapturedNPCMetadata resolveCapturedMetadata(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        try {
            return itemStack.getFromMetadataOrNull(CapturedNPCMetadata.KEYED_CODEC);
        } catch (Exception ex) {
            logger.at(Level.FINE).withCause(ex).log(
                    "Spawner stub: failed to read captured NPC metadata from item."
            );
            return null;
        }
    }

    // Compatibility shim for evolving CapturedNPCMetadata getters/setters across pre-release builds.
    private static String readStringGetter(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            String value = invokeStringGetter(target, methodName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Integer readIntGetter(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            Integer value = invokeIntGetter(target, methodName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String invokeStringGetter(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private static Integer invokeIntGetter(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Integer) {
                return (Integer) value;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return null;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private static boolean invokeStringSetter(Object target, String methodName, String value) {
        if (target == null || methodName == null || value == null || value.isBlank()) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            method.invoke(target, value);
            return true;
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    private static boolean invokeIntSetter(Object target, String methodName, int value) {
        if (target == null || methodName == null) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, int.class);
            method.invoke(target, value);
            return true;
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    // Called by NPC action chains to capture an NPC into the held spawner item.
    public boolean captureFromNpcAction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack, ItemFeatureConfig config) {
        if (player == null || targetRef == null || itemStack == null || config == null) {
            return false;
        }
        if (isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        if (!canCapture(player, targetRef, config, itemStack)) {
            return false;
        }
        CaptureInfo captureInfo = buildCaptureInfo(player, targetRef);
        String attachmentsJson = captureInfo.attachmentsJson;
        if (attachmentsJson != null && !attachmentsJson.isBlank()) {
            logger.at(Level.FINE).log(
                    "Spawner capture attachments: item=" + itemStack.getItemId() + " attachments=" + attachmentsJson
            );
        }
        logger.at(Level.FINE).log(
                "Spawner capture debug: item=" + itemStack.getItemId()
                        + " modelAssetId=" + resolveModelAssetId(player, targetRef)
                        + " attachmentsPresent=" + (attachmentsJson != null && !attachmentsJson.isBlank())
        );
        String fullItemIcon = resolveFullItemIcon(config, attachmentsJson, itemStack.getItemId(), captureInfo.npcNameKey);

        UUID targetUuid = resolveEntityUuid(player, targetRef);
        UUID existingOwner = resolveOwnerFromComponent(targetRef, player.getWorld());
        UUID ownerToStore = null;
        if (!config.isCaptureClearsOwner()) {
            ownerToStore = existingOwner != null ? existingOwner : player.getUuid();
        }

        ItemStack updated = swapItemId(itemStack, config.getSpawnerFilledItemId())
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING, targetUuid);
        if (attachmentsJson != null) {
            updated = updated.withMetadata(TameworkMetadataKeys.ATTACHMENTS, Codec.STRING, attachmentsJson);
        }
        boolean tamed = resolveTamedFromComponent(targetRef, player.getWorld());
        if (tamed) {
            updated = updated.withMetadata(TameworkMetadataKeys.TAMED, Codec.BOOLEAN, true);
        }
        updated = applyOwnerMetadata(updated, ownerToStore);
        updated = applyCapturedMetadata(updated, captureInfo, fullItemIcon);
        updated = applyCapturedNameMetadata(updated, captureInfo);
        updated = applyCooldown(updated, TameworkMetadataKeys.CAPTURE_COOLDOWN_UNTIL, config.getCaptureCooldownMs());

        if (!updateHeldItem(player, updated)) {
            logger.at(Level.WARNING).log("Spawner stub: failed to update held item.");
            return false;
        }
        spawnCaptureParticles(player.getWorld(), targetRef, config);
        clearOwnerIfConfigured(player, config, targetRef);
        despawnNpc(player, targetRef, null);

        logger.at(Level.FINE).log(
                "Spawner stub: capture request item=" + itemStack.getItemId()
                        + " targetUuid=" + targetUuid
                        + " captureClearsOwner=" + config.isCaptureClearsOwner()
        );
        return true;
    }
    private boolean resolveCaptureRequireOwner(ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        Boolean override = config.getCaptureRequireOwnerOverride();
        return override != null ? override : false;
    }
    private boolean resolveSpawnRequireOwner(ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        Boolean override = config.getSpawnRequireOwnerOverride();
        return override != null ? override : false;
    }

    private boolean isCaptureAllowedByOwnership(UUID playerUuid, UUID ownerUuid, ItemFeatureConfig config) {
        boolean ownerRestricted = config.isCaptureOwnerRestricted();
        boolean requireOwner = resolveCaptureRequireOwner(config);
        if (ownerUuid != null) {
            if (ownerRestricted && (playerUuid == null || !ownerUuid.equals(playerUuid))) {
                return false;
            }
            return true;
        }
        if (requireOwner) {
            return false;
        }
        return true;
    }

    private boolean isSpawnAllowedByOwnership(UUID playerUuid, UUID ownerUuid, ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        boolean requireOwner = resolveSpawnRequireOwner(config);
        if (ownerUuid != null) {
            if (config.isSpawnOwnerRestricted() && (playerUuid == null || !ownerUuid.equals(playerUuid))) {
                return false;
            }
            return true;
        }
        if (requireOwner) {
            return false;
        }
        return true;
    }

    private boolean canCapture(Player player, Ref<EntityStore> targetRef, ItemFeatureConfig config, ItemStack itemStack) {
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
        String roleId = resolveRoleIdFromNpc(npc);
        if (!isRoleAllowed(roleId, config)) {
            return false;
        }
        if (config.isCaptureRequireTamed() && !resolveTamedFromComponent(targetRef, world)) {
            String npcName = resolveNpcDisplayName(npc);
            OwnerMessageUtil.sendUntamed(player, npcName);
            return false;
        }
        UUID ownerUuid = resolveOwnerFromComponent(targetRef, world);
        if (!isCaptureAllowedByOwnership(player.getUuid(), ownerUuid, config)) {
            if (ownerUuid != null) {
                String npcName = resolveNpcDisplayName(npc);
                String ownerName = resolveOwnerNameFromComponent(targetRef, world);
                OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "capture");
            }
            return false;
        }
        return isWithinCaptureDistance(player, targetRef, config, store);
    }

    private String resolveRoleIdFromNpc(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            String nameKey = NPCPlugin.get().getName(roleIndex);
            if (nameKey != null && !nameKey.isBlank()) {
                return nameKey;
            }
        }
        return null;
    }

    private boolean isRoleAllowed(String roleId, ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        ItemFeatureConfig.RoleListMode mode = config.getSpawnerRoleListMode();
        if (mode == null || mode == ItemFeatureConfig.RoleListMode.ANY) {
            return true;
        }
        if (mode == ItemFeatureConfig.RoleListMode.ALLOW) {
            if (roleId == null || roleId.isBlank()) {
                return false;
            }
            List<String> allow = config.getSpawnerRoleAllowlist();
            return allow != null && allow.contains(roleId);
        }
        if (mode == ItemFeatureConfig.RoleListMode.DENY) {
            List<String> deny = config.getSpawnerRoleDenylist();
            if (deny == null || deny.isEmpty()) {
                return true;
            }
            if (roleId == null || roleId.isBlank()) {
                return true;
            }
            return !deny.contains(roleId);
        }
        return true;
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

    private boolean isWithinSpawnDistance(Player player, Vector3d spawnPosition, ItemFeatureConfig config) {
        if (player == null || spawnPosition == null || config == null) {
            return false;
        }
        double maxDistance = config.getSpawnMaxDistance();
        if (maxDistance <= 0) {
            return true;
        }
        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return false;
        }
        Vector3d p = new Vector3d(playerTransform.getPosition());
        double dx = p.x - spawnPosition.x;
        double dy = p.y - spawnPosition.y;
        double dz = p.z - spawnPosition.z;
        double maxDistSq = maxDistance * maxDistance;
        return (dx * dx + dy * dy + dz * dz) <= maxDistSq;
    }

    private Vector3d resolveSpawnPosition(Player player, ItemFeatureConfig config) {
        if (player == null) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        double maxDistance = config != null ? config.getSpawnMaxDistance() : 0;
        double spawnDistance = maxDistance > 0 ? maxDistance : SPAWN_FORWARD_DISTANCE;
        double rayDistance = spawnDistance + RAYCAST_DISTANCE_EPSILON;

        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3f rotation = new Vector3f(transform.getRotation());
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = new Vector3f(headRotation.getRotation());
        }

        Vector3f forward = new Vector3f(Vector3f.FORWARD);
        forward.rotateY(rotation.getYaw());
        forward.rotateX(rotation.getPitch());
        forward.normalize();

        Vector3d targetLocation = TargetUtil.getTargetLocation(
            playerRef,
            blockId -> isBlockingSpawnBlock(blockId),
            rayDistance,
            store
        );
        if (targetLocation != null) {
            // Nudge the hit position back along the ray so we reliably resolve the hit block,
            // then spawn centered on top of that block to avoid inside-block spawns.
            double nudge = 0.01;
            Vector3d adjusted = new Vector3d(
                targetLocation.x - forward.x * nudge,
                targetLocation.y - forward.y * nudge,
                targetLocation.z - forward.z * nudge
            );
            int blockX = (int) Math.floor(adjusted.x);
            int blockY = (int) Math.floor(adjusted.y);
            int blockZ = (int) Math.floor(adjusted.z);
            double clampedX = Math.min(Math.max(targetLocation.x, blockX + 0.001), blockX + 0.999);
            double clampedZ = Math.min(Math.max(targetLocation.z, blockZ + 0.001), blockZ + 0.999);
            double targetY = targetLocation.y;
            double spawnY;
            double snapThreshold = 0.02;
            if (Math.abs(targetY - blockY) <= snapThreshold || Math.abs(targetY - (blockY + 1.0)) <= snapThreshold) {
                spawnY = targetY + SPAWN_SURFACE_OFFSET_Y;
            } else {
                spawnY = blockY + 1.0 + SPAWN_SURFACE_OFFSET_Y;
            }
            Vector3d spawnPos = new Vector3d(
                clampedX,
                spawnY,
                clampedZ
            );
            logSpawnDebug("hit", targetLocation, adjusted, spawnPos, forward, rayDistance, maxDistance, blockX, blockY, blockZ);
            return spawnPos;
        }

        Vector3d spawnPos = new Vector3d(transform.getPosition());
        spawnPos.x += forward.x * spawnDistance;
        spawnPos.y += forward.y * spawnDistance + SPAWN_OFFSET_Y;
        spawnPos.z += forward.z * spawnDistance;
        double minY = transform.getPosition().y + SPAWN_SURFACE_OFFSET_Y;
        if (spawnPos.y < minY) {
            spawnPos.y = minY;
            logSpawnDebug("fallback-clamped", null, null, spawnPos, forward, spawnDistance, maxDistance, -1, -1, -1);
            return spawnPos;
        }
        logSpawnDebug("fallback", null, null, spawnPos, forward, spawnDistance, maxDistance, -1, -1, -1);
        return spawnPos;
    }

    private boolean isBlockingSpawnBlock(int blockId) {
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null || blockType == BlockType.UNKNOWN) {
            return false;
        }
        return WorldUtil.isSolidOnlyBlock(blockType, 0);
    }





    private void logSpawnDebug(
            String stage,
            Vector3d targetLocation,
            Vector3d adjusted,
            Vector3d spawnPos,
            Vector3f forward,
            double rayDistance,
            double maxDistance,
            int blockX,
            int blockY,
            int blockZ
    ) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugSpawnerEnabled()) {
            return;
        }
        StringBuilder message = new StringBuilder(200);
        message.append("Spawner spawn debug [").append(stage).append("] ");
        message.append("ray=").append(rayDistance).append(" max=").append(maxDistance).append(" ");
        if (forward != null) {
            message.append("forward=").append(formatVector(forward)).append(" ");
        }
        if (targetLocation != null) {
            message.append("target=").append(formatVector(targetLocation)).append(" ");
        }
        if (adjusted != null) {
            message.append("adjusted=").append(formatVector(adjusted)).append(" ");
        }
        if (blockX != -1 || blockY != -1 || blockZ != -1) {
            message.append("block=(").append(blockX).append(",").append(blockY).append(",").append(blockZ).append(") ");
        }
        if (spawnPos != null) {
            message.append("spawn=").append(formatVector(spawnPos));
        }
        logger.at(Level.INFO).log(message.toString());
    }

    private static String formatVector(Vector3d vector) {
        if (vector == null) {
            return "(null)";
        }
        return String.format(Locale.US, "(%.3f, %.3f, %.3f)", vector.x, vector.y, vector.z);
    }

    private static String formatVector(Vector3f vector) {
        if (vector == null) {
            return "(null)";
        }
        return String.format(Locale.US, "(%.3f, %.3f, %.3f)", vector.x, vector.y, vector.z);
    }

    private Vector3f resolveSpawnRotation(Store<EntityStore> store,
                                         Ref<EntityStore> playerRef,
                                         Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Vector3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Vector3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                playerPos.x - spawnPosition.x,
                0.0,
                playerPos.z - spawnPosition.z
            );
            if (relative.squaredLength() > 0.0001) {
                return Vector3f.lookAt(relative);
            }
        }
        Vector3f rotation = new Vector3f(transform.getRotation());
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        if (headRotation != null) {
            rotation = new Vector3f(headRotation.getRotation());
        }
        return rotation;
    }

    private void spawnSpawnParticles(World world, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (config == null) {
            return;
        }
        spawnEffects(world, targetRef, config.getSpawnParticleSystem(), config.getSpawnSoundEvent());
    }

    private void spawnCaptureParticles(World world, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (config == null) {
            return;
        }
        spawnEffects(world, targetRef, config.getCaptureParticleSystem(), config.getCaptureSoundEvent());
    }

    private void spawnEffects(World world, Ref<EntityStore> targetRef, String particleSystem, String soundEvent) {
        if (world == null || targetRef == null || !targetRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        List<Ref<EntityStore>> playerRefs = resolvePlayerRefs(world);
        if (particleSystem != null && !particleSystem.isBlank() && !playerRefs.isEmpty()) {
            ParticleUtil.spawnParticleEffect(particleSystem, position, targetRef, playerRefs, store);
        }
        if (soundEvent != null && !soundEvent.isBlank()) {
            int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
            if (soundEventIndex > 0) {
                SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, position, store);
            }
        }
    }

    private List<Ref<EntityStore>> resolvePlayerRefs(World world) {
        if (world == null) {
            return List.of();
        }
        List<Ref<EntityStore>> refs = new ArrayList<>();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (playerRef == null) {
                continue;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                refs.add(ref);
            }
        }
        return refs;
    }    private boolean isCooldownActive(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return false;
        }
        Long until = itemStack.getFromMetadataOrNull(key, Codec.LONG);
        if (until == null) {
            return false;
        }
        return until > System.currentTimeMillis();
    }

    private ItemStack applyCooldown(ItemStack itemStack, String key, int cooldownMs) {
        if (itemStack == null || key == null || cooldownMs <= 0) {
            return itemStack;
        }
        long until = System.currentTimeMillis() + cooldownMs;
        return itemStack.withMetadata(key, Codec.LONG, until);
    }



    private boolean isAlreadyCaptured(ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        String itemId = itemStack.getItemId();
        if (itemId != null && itemId.contains("_State_")) {
            return true;
        }
        Boolean captured = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURED,
                Codec.BOOLEAN
        );
        return Boolean.TRUE.equals(captured);
    }

    private boolean isFilledItem(ItemStack itemStack, ItemFeatureConfig config) {
        if (itemStack == null || itemStack.getItemId() == null) {
            return false;
        }
        String itemId = itemStack.getItemId();
        if (itemId.contains("_State_")) {
            return true;
        }
        Boolean captured = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURED,
                Codec.BOOLEAN
        );
        if (captured != null) {
            return captured;
        }
        return false;
    }

    private void applyOwner(ItemFeatureConfig config,
                            Ref<EntityStore> npcRef,
                            NPCEntity npc,
                            Ref<EntityStore> playerRef,
                            UUID ownerUuid,
                            World world) {
        if (npc == null) {
            return;
        }
        if (world != null && npcRef != null && npcRef.isValid()) {
            Store<EntityStore> store = world.getEntityStore().getStore();
            ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
            if (type != null) {
                String ownerName = null;
                if (ownerUuid != null) {
                    Player ownerPlayer = null;
                    if (playerRef != null) {
                        ownerPlayer = store.getComponent(playerRef, Player.getComponentType());
                    }
                    if (ownerPlayer != null && ownerUuid.equals(ownerPlayer.getUuid())) {
                        ownerName = OwnerNameUtil.resolve(ownerPlayer);
                    } else {
                        Ref<EntityStore> ownerRef = world.getEntityRef(ownerUuid);
                        if (ownerRef != null) {
                            Player resolvedOwner = store.getComponent(ownerRef, Player.getComponentType());
                            if (resolvedOwner != null) {
                                ownerName = OwnerNameUtil.resolve(resolvedOwner);
                            }
                        }
                    }
                }
                store.putComponent(npcRef, type, new TameworkOwnerComponent(ownerUuid, ownerName));
            }
        }
        if (!config.isSpawnAssignsOwner()) {
            return;
        }
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        Ref<EntityStore> ownerRef = playerRef;
        if (ownerUuid != null && world != null) {
            Ref<EntityStore> resolved = world.getEntityRef(ownerUuid);
            if (resolved != null) {
                ownerRef = resolved;
            }
        }
        if (ownerRef != null) {
            role.setMarkedTarget(MASTER_TARGET_SLOT, ownerRef);
        }
    }

    private void applyTamed(Ref<EntityStore> npcRef, boolean tamed, World world) {
        if (npcRef == null || !npcRef.isValid() || world == null) {
            return;
        }
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.putComponent(npcRef, type, new TameworkTamedComponent(tamed));
    }

    private void applyCapturedName(ItemStack itemStack, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (itemStack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        String name = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME, Codec.STRING);
        if (name == null || name.isBlank()) {
            return;
        }
        UUID ownerId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_OWNER_UUID, Codec.UUID_STRING);
        Long updatedMs = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_UPDATED_MS, Codec.LONG);
        String sourceRaw = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_SOURCE, Codec.STRING);
        TameworkNpcNameComponent.NameSource source = parseNameSource(sourceRaw);
        if (source == null) {
            source = TameworkNpcNameComponent.NameSource.Player;
        }
        long resolvedUpdatedMs = (updatedMs != null && updatedMs > 0) ? updatedMs : System.currentTimeMillis();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            store.putComponent(npcRef, nameType, new TameworkNpcNameComponent(name, ownerId, resolvedUpdatedMs, source));
        }
        EntitySupport.setDisplayName(npcRef, name, store);
    }

    private TameworkNpcNameComponent.NameSource parseNameSource(String sourceRaw) {
        if (sourceRaw == null || sourceRaw.isBlank()) {
            return null;
        }
        try {
            return TameworkNpcNameComponent.NameSource.valueOf(sourceRaw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void applyAttachments(ItemStack itemStack,
                                  Ref<EntityStore> npcRef,
                                  NPCEntity npc,
                                  Store<EntityStore> store) {
        String attachmentsJson = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.ATTACHMENTS,
                Codec.STRING
        );
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return;
        }
        Map<String, String> attachments;
        try {
            attachments = GSON.fromJson(attachmentsJson, ATTACHMENT_MAP_TYPE);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Spawner stub: failed to parse attachment metadata.");
            return;
        }
        if (attachments == null) {
            return;
        }

        ModelComponent modelComponent = store.getComponent(npcRef, ModelComponent.getComponentType());
        if (modelComponent == null) {
            return;
        }
        Model model = modelComponent.getModel();
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(model.getModelAssetId());
        if (modelAsset == null) {
            logger.at(Level.WARNING).log("Spawner stub: missing model asset for attachments.");
            return;
        }

        Map<String, String> applied = new HashMap<>(attachments);
        Model updatedModel = Model.createScaledModel(modelAsset, model.getScale(), applied);
        store.putComponent(npcRef, ModelComponent.getComponentType(), new ModelComponent(updatedModel));
        if (npc != null && npc.getRole() != null) {
            npc.getRole().updateMotionControllers(npcRef, updatedModel, updatedModel.getBoundingBox(), store);
        }
    }



    // Collect attachments, role/name keys, and icon overrides from the target NPC.
    private CaptureInfo buildCaptureInfo(Player player, Ref<EntityStore> targetRef) {
        if (player == null || targetRef == null || !targetRef.isValid()) {
            return new CaptureInfo(null, null, null, null, null);
        }
        World world = player.getWorld();
        if (world == null) {
            return new CaptureInfo(null, null, null, null, null);
        }
        Store<EntityStore> store = world.getEntityStore().getStore();

        String attachmentsJson = null;
        String iconPath = null;
        ModelComponent modelComponent = store.getComponent(targetRef, ModelComponent.getComponentType());
        if (modelComponent != null) {
            Model model = modelComponent.getModel();
            if (model != null) {
                Map<String, String> attachments = model.getRandomAttachmentIds();
                if (attachments != null) {
                    Map<String, String> snapshot = new HashMap<>(attachments);
                    attachmentsJson = GSON.toJson(snapshot, ATTACHMENT_MAP_TYPE);
                }
                ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(model.getModelAssetId());
                if (modelAsset != null) {
                    iconPath = modelAsset.getIcon();
                }
            }
        }

        Integer roleIndex = null;
        String npcNameKey = null;
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            int resolvedRoleIndex = npc.getRoleIndex();
            if (resolvedRoleIndex >= 0) {
                roleIndex = resolvedRoleIndex;
                String nameKey = NPCPlugin.get().getName(resolvedRoleIndex);
                if (nameKey != null && !nameKey.isBlank()) {
                    npcNameKey = nameKey;
                }
            }
        }

        CapturedName capturedName = null;
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            TameworkNpcNameComponent nameComponent = store.getComponent(targetRef, nameType);
            if (nameComponent != null) {
                String name = nameComponent.getName();
                if (name != null && !name.isBlank()) {
                    capturedName = new CapturedName(
                            name,
                            nameComponent.getOwnerId(),
                            nameComponent.getLastUpdatedMs(),
                            nameComponent.getSource()
                    );
                }
            }
        }

        return new CaptureInfo(attachmentsJson, roleIndex, npcNameKey, iconPath, capturedName);
    }


    private String resolveModelAssetId(Player player, Ref<EntityStore> targetRef) {
        if (player == null || targetRef == null || !targetRef.isValid()) {
            return "<none>";
        }
        World world = player.getWorld();
        if (world == null) {
            return "<no-world>";
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        ModelComponent modelComponent = store.getComponent(targetRef, ModelComponent.getComponentType());
        if (modelComponent == null || modelComponent.getModel() == null) {
            return "<no-model>";
        }
        return modelComponent.getModel().getModelAssetId();
    }

    // Writes CapturedNPCMetadata (role/name/icon) onto the spawner item.
    // Persist capture info (role, icon, attachments, optional name) onto the item.
    private ItemStack applyCapturedMetadata(ItemStack updated, CaptureInfo captureInfo, String fullItemIcon) {
        if (updated == null || captureInfo == null) {
            return updated;
        }
        CapturedNPCMetadata meta = new CapturedNPCMetadata();
        boolean wrote = false;

        Integer roleIndex = captureInfo.roleIndex;
        if (roleIndex != null && roleIndex >= 0) {
            wrote |= invokeIntSetter(meta, "setRoleIndex", roleIndex);
        }
        String npcNameKey = captureInfo.npcNameKey;
        if (npcNameKey != null && !npcNameKey.isBlank()) {
            wrote |= invokeStringSetter(meta, "setNpcNameKey", npcNameKey);
            wrote |= invokeStringSetter(meta, "setRoleNameKey", npcNameKey);
            wrote |= invokeStringSetter(meta, "setRoleId", npcNameKey);
            wrote |= invokeStringSetter(meta, "setRoleKey", npcNameKey);
        }
        String icon = (fullItemIcon != null && !fullItemIcon.isBlank()) ? fullItemIcon : captureInfo.iconPath;
        if (icon != null && !icon.isBlank()) {
            wrote |= invokeStringSetter(meta, "setIconPath", icon);
        }
        if (fullItemIcon != null && !fullItemIcon.isBlank()) {
            wrote |= invokeStringSetter(meta, "setFullItemIcon", fullItemIcon);
        }
        if (!wrote) {
            return updated;
        }
        return updated.withMetadata(CapturedNPCMetadata.KEYED_CODEC, meta);
    }

    private ItemStack applyCapturedNameMetadata(ItemStack updated, CaptureInfo captureInfo) {
        if (updated == null || captureInfo == null) {
            return updated;
        }
        CapturedName capturedName = captureInfo.capturedName;
        if (capturedName == null || capturedName.name == null || capturedName.name.isBlank()) {
            return clearNameMetadata(updated);
        }
        ItemStack result = updated.withMetadata(TameworkMetadataKeys.NPC_NAME, Codec.STRING, capturedName.name);
        if (capturedName.ownerId != null) {
            result = result.withMetadata(
                    TameworkMetadataKeys.NPC_NAME_OWNER_UUID,
                    Codec.UUID_STRING,
                    capturedName.ownerId
            );
        } else {
            result = clearMetadataKey(result, TameworkMetadataKeys.NPC_NAME_OWNER_UUID);
        }
        long updatedMs = capturedName.updatedMs > 0 ? capturedName.updatedMs : System.currentTimeMillis();
        result = result.withMetadata(TameworkMetadataKeys.NPC_NAME_UPDATED_MS, Codec.LONG, updatedMs);
        if (capturedName.source != null) {
            result = result.withMetadata(
                    TameworkMetadataKeys.NPC_NAME_SOURCE,
                    Codec.STRING,
                    capturedName.source.name()
            );
        } else {
            result = clearMetadataKey(result, TameworkMetadataKeys.NPC_NAME_SOURCE);
        }
        return result;
    }

    private ItemStack clearNameMetadata(ItemStack updated) {
        ItemStack cleared = clearMetadataKey(updated, TameworkMetadataKeys.NPC_NAME);
        cleared = clearMetadataKey(cleared, TameworkMetadataKeys.NPC_NAME_OWNER_UUID);
        cleared = clearMetadataKey(cleared, TameworkMetadataKeys.NPC_NAME_UPDATED_MS);
        cleared = clearMetadataKey(cleared, TameworkMetadataKeys.NPC_NAME_SOURCE);
        return cleared;
    }

    private ItemFeatureConfig resolveIconConfig(ItemFeatureConfig config) {
        if (config == null) {
            return null;
        }
        String filledId = config.getSpawnerFilledItemId();
        if (filledId == null || filledId.isBlank() || registry == null) {
            return config;
        }
        ItemFeatureConfig filledConfig = registry.get(filledId);
        return filledConfig != null ? filledConfig : config;
    }

    // Resolve icon overrides based on captured attachments (falls back to default icon).
    // Resolve a per-attachment icon override or fall back to default.
    private String resolveFullItemIcon(ItemFeatureConfig config, String attachmentsJson, String itemId, String roleId) {
        ItemFeatureConfig resolved = resolveIconConfig(config);
        if (resolved == null) {
            return null;
        }
        String defaultIcon = resolved.getSpawnerIconDefault();
        Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> overridesByRole = resolved.getSpawnerIconOverridesByRole();
        List<ItemFeatureConfig.SpawnerIconOverride> roleOverrides = null;
        if (roleId != null && overridesByRole != null && !overridesByRole.isEmpty()) {
            roleOverrides = overridesByRole.get(roleId);
        }
        List<ItemFeatureConfig.SpawnerIconOverride> overrides = resolved.getSpawnerIconOverrides();
        boolean hasRoleOverrides = roleOverrides != null && !roleOverrides.isEmpty();
        boolean hasGlobalOverrides = overrides != null && !overrides.isEmpty();
        if (!hasRoleOverrides && !hasGlobalOverrides) {
            return defaultIcon;
        }
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return defaultIcon;
        }

        Map<String, String> attachments;
        try {
            attachments = GSON.fromJson(attachmentsJson, ATTACHMENT_MAP_TYPE);
        } catch (Exception ex) {
            logger.at(Level.WARNING).withCause(ex).log("Spawner icon override: failed to parse attachments.");
            return defaultIcon;
        }
        if (attachments == null) {
            return defaultIcon;
        }

        if (hasRoleOverrides) {
            for (ItemFeatureConfig.SpawnerIconOverride override : roleOverrides) {
                if (override == null) {
                    continue;
                }
                if (matchesAttachments(override.getAttachments(), attachments)) {
                    String icon = override.getIcon();
                    logger.at(Level.FINE).log(
                            "Spawner icon override (role): matched item=" + itemId
                                    + " role=" + roleId
                                    + " icon=" + icon
                                    + " attachments=" + attachmentsJson
                    );
                    return icon;
                }
            }
        }

        if (hasGlobalOverrides) {
            for (ItemFeatureConfig.SpawnerIconOverride override : overrides) {
                if (override == null) {
                    continue;
                }
                if (matchesAttachments(override.getAttachments(), attachments)) {
                    String icon = override.getIcon();
                    logger.at(Level.FINE).log(
                            "Spawner icon override: matched item=" + itemId + " icon=" + icon + " attachments=" + attachmentsJson
                    );
                    return icon;
                }
            }
        }

        logger.at(Level.FINE).log(
                "Spawner icon override: no match item=" + itemId + " role=" + roleId + " attachments=" + attachmentsJson
        );
        return defaultIcon;
    }

    private boolean matchesAttachments(Map<String, String> required, Map<String, String> actual) {
        if (required == null || required.isEmpty()) {
            return false;
        }
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            String value = actual.get(entry.getKey());
            if (value == null || !value.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }




    private void despawnNpc(Player player, Ref<EntityStore> targetRef, Entity targetEntity) {
        if (player == null) {
            return;
        }
        if (targetEntity instanceof NPCEntity npcEntity) {
            npcEntity.setToDespawn();
            return;
        }
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            npc.setToDespawn();
        }
    }

    private ItemStack swapItemId(ItemStack stack, String itemId) {
        if (stack == null || itemId == null || itemId.isBlank()) {
            return stack;
        }
        if (itemId.equals(stack.getItemId())) {
            return stack;
        }
        return new ItemStack(
                itemId,
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                stack.getMetadata()
        );
    }

    // Removes captured metadata and resets the item to its empty state.
    private ItemStack clearCapturedMetadata(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.CAPTURED);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TARGET_UUID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TARGET_ENTITY_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.ATTACHMENTS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.OWNER_UUID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TAMED);
        updated = clearNameMetadata(updated);
        updated = updated.withMetadata(CapturedNPCMetadata.KEYED_CODEC, null);
        return updated;
    }

    private String resolveEmptyItemId(String currentItemId) {
        if (currentItemId == null || registry == null) {
            return null;
        }
        String normalized = ItemFeatureRegistry.normalizeStateItemId(currentItemId);
        for (Map.Entry<String, ItemFeatureConfig> entry : registry.snapshot().entrySet()) {
            ItemFeatureConfig cfg = entry.getValue();
            if (cfg == null) {
                continue;
            }
            String filledId = cfg.getSpawnerFilledItemId();
            if (filledId == null || filledId.isBlank()) {
                continue;
            }
            String normalizedFilled = ItemFeatureRegistry.normalizeStateItemId(filledId);
            if (normalized != null && normalized.equals(normalizedFilled)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private ItemStack applyOwnerMetadata(ItemStack updated, UUID ownerUuid) {
        if (updated == null) {
            return null;
        }
        if (ownerUuid == null) {
            return clearMetadataKey(updated, TameworkMetadataKeys.OWNER_UUID);
        }
        return updated.withMetadata(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING, ownerUuid);
    }

    private ItemStack clearMetadataKey(ItemStack stack, String key) {
        if (stack == null || key == null) {
            return stack;
        }
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null || !metadata.containsKey(key)) {
            return stack;
        }
        BsonDocument copy = metadata.clone();
        copy.remove(key);
        return stack.withMetadata(copy);
    }

    private boolean resolveTamedFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkTamedComponent component = store.getComponent(targetRef, type);
        return component != null && component.isTamed();
    }

    private UUID resolveOwnerFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent component = store.getComponent(targetRef, type);
        return component != null ? component.getOwnerId() : null;
    }

    private String resolveOwnerNameFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent component = store.getComponent(targetRef, type);
        return component != null ? component.getOwnerName() : null;
    }

    private String resolveNpcDisplayName(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        TranslationRegistry registry = null;
        Tamework instance = Tamework.getInstance();
        if (instance != null) {
            registry = instance.getTranslationRegistry();
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            String nameKey = NPCPlugin.get().getName(roleIndex);
            if (nameKey != null && !nameKey.isBlank()) {
                String translated = registry != null ? registry.get(nameKey) : null;
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
                if (registry != null && !nameKey.contains(".")) {
                    String derivedKey = "npcRoles." + nameKey + ".name";
                    translated = registry.get(derivedKey);
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                }
                return nameKey;
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            if (registry != null) {
                String derivedKey = "npcRoles." + roleName + ".name";
                String translated = registry.get(derivedKey);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            return roleName;
        }
        return null;
    }

    private UUID resolveEntityUuid(Player player, Ref<EntityStore> targetRef) {
        if (player == null || targetRef == null || !targetRef.isValid()) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    private void clearOwnerIfConfigured(Player player, ItemFeatureConfig config, Ref<EntityStore> targetRef) {
        if (player == null || !config.isCaptureClearsOwner() || targetRef == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type != null) {
            store.putComponent(targetRef, type, new TameworkOwnerComponent(null, null));
        }
        npc.getRole().getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, null);
    }

    private Ref<EntityStore> resolveEntityRef(Player player, Integer entityId, UUID entityUuid) {
        if (player == null) {
            return null;
        }
        World world = player.getWorld();
        if (world == null) {
            return null;
        }
        if (entityUuid != null) {
            return world.getEntityRef(entityUuid);
        }
        if (entityId == null || entityId <= 0) {
            return null;
        }
        return world.getEntityStore().getRefFromNetworkId(entityId);
    }

    
    private ItemStack getHotbarItem(Player player, int slot) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return null;
        }
        return hotbar.getItemStack((short) slot);
    }

    // Update the active hotbar slot and push inventory changes to the client.
    private boolean updateHeldItem(Player player, ItemStack updated) {
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        byte activeSlot = inventory.getActiveHotbarSlot();
        hotbar.setItemStackForSlot((short) activeSlot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }




    // Update a specific hotbar slot when the packet provides the slot index.
    private boolean updateHotbarSlot(Player player, int slot, ItemStack updated) {
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            return false;
        }
        hotbar.setItemStackForSlot((short) slot, updated);
        inventory.markChanged();
        player.sendInventory();
        return true;
    }
}


























