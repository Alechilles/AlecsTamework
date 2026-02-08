package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
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
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.math.util.ChunkUtil;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import com.hypixel.hytale.server.npc.role.Role;
import it.unimi.dsi.fastutil.Pair;
import org.bson.BsonDocument;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Capture/spawn logic for spawner items, including metadata and attachments.
 */
public final class SpawnerFeatureHandler {

    private static final double SPAWN_OFFSET_Y = 0.5;
    private static final double SPAWN_FORWARD_DISTANCE = 1.5;
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final Gson GSON = new Gson();
    private static final Type ATTACHMENT_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private static final class CaptureInfo {
        private final String attachmentsJson;
        private final Integer roleIndex;
        private final String npcNameKey;
        private final String iconPath;

        private CaptureInfo(String attachmentsJson, Integer roleIndex, String npcNameKey, String iconPath) {
            this.attachmentsJson = attachmentsJson;
            this.roleIndex = roleIndex;
            this.npcNameKey = npcNameKey;
            this.iconPath = iconPath;
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
                                            String roleIdOverride,
                                            String emptyItemIdOverride,
                                            Boolean spawnAssignsOwnerOverride,
                                            Boolean allowUncapturedOverride) {
        ItemFeatureConfig baseConfig = resolveConfigForItem(itemStack);
        String resolvedRoleId = resolveRoleId(roleIdOverride, itemStack, baseConfig);
        if (resolvedRoleId == null || resolvedRoleId.isBlank()) {
            String itemId = itemStack != null ? itemStack.getItemId() : "<null>";
            logger.at(Level.WARNING).log(
                    "Spawner stub: missing SpawnerRoleId for item=" + itemId
            );
            return false;
        }
        boolean spawnAssignsOwner = spawnAssignsOwnerOverride != null
                ? spawnAssignsOwnerOverride
                : baseConfig != null && baseConfig.isSpawnAssignsOwner();
        boolean allowUncaptured = allowUncapturedOverride != null
                ? allowUncapturedOverride
                : baseConfig != null && baseConfig.isSpawnerAllowUncaptured();

        String spawnerParticleSystem = baseConfig != null ? baseConfig.getSpawnerParticleSystem() : null;
        String spawnerSoundEvent = baseConfig != null ? baseConfig.getSpawnerSoundEvent() : null;

        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerRoleId(resolvedRoleId)
                .spawnAssignsOwner(spawnAssignsOwner)
                .spawnerAllowUncaptured(allowUncaptured)
                .spawnerParticleSystem(spawnerParticleSystem)
                .spawnerSoundEvent(spawnerSoundEvent)
                .build();
        return spawnFromItem(player, itemStack, config, null, emptyItemIdOverride);
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

    private String resolveRoleId(String roleIdOverride, ItemStack itemStack, ItemFeatureConfig baseConfig) {
        if (roleIdOverride != null && !roleIdOverride.isBlank()) {
            return roleIdOverride;
        }
        CapturedNPCMetadata meta = resolveCapturedMetadata(itemStack);
        if (meta != null) {
            int roleIndex = meta.getRoleIndex();
            if (roleIndex >= 0) {
                String roleName = NPCPlugin.get().getName(roleIndex);
                if (roleName != null && !roleName.isBlank()) {
                    return roleName;
                }
            }
            String npcNameKey = meta.getNpcNameKey();
            if (npcNameKey != null && !npcNameKey.isBlank()) {
                return npcNameKey;
            }
        }
        if (baseConfig != null) {
            String roleId = baseConfig.getSpawnerRoleId();
            if (roleId != null && !roleId.isBlank()) {
                return roleId;
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
        if (!canCapture(player, targetRef, config)) {
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
                        + " roleId=" + config.getSpawnerRoleId()
                        + " modelAssetId=" + resolveModelAssetId(player, targetRef)
                        + " attachmentsPresent=" + (attachmentsJson != null && !attachmentsJson.isBlank())
        );
        String fullItemIcon = resolveFullItemIcon(config, attachmentsJson, itemStack.getItemId(), captureInfo.npcNameKey);

        UUID targetUuid = resolveEntityUuid(player, targetRef);
        UUID existingOwner = resolveOwnerFromComponent(targetRef, player.getWorld());
        if (config.isOwnerRestricted() && existingOwner != null && !existingOwner.equals(player.getUuid())) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (not owner) item=" + itemStack.getItemId()
                            + " targetUuid=" + targetUuid
            );
            return false;
        }
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

    // Adjust spawn position to avoid spawning inside blocks directly in front of the player.
    // Find a nearby clear space in front of the player for safe spawning.
    private Vector3d resolveSafeSpawnPosition(World world, Vector3d desired, Vector3f forward) {
        if (world == null || desired == null) {
            return desired;
        }
        if (isClearSpace(world, desired)) {
            return desired;
        }
        Vector3d up = new Vector3d(desired.x, desired.y + 1.0, desired.z);
        if (isClearSpace(world, up)) {
            return up;
        }
        if (forward != null) {
            double[] steps = new double[] {0.5, 1.0, 1.5};
            for (double step : steps) {
                Vector3d back = new Vector3d(
                        desired.x - forward.x * step,
                        desired.y,
                        desired.z - forward.z * step
                );
                if (isClearSpace(world, back)) {
                    return back;
                }
                Vector3d backUp = new Vector3d(back.x, back.y + 1.0, back.z);
                if (isClearSpace(world, backUp)) {
                    return backUp;
                }
            }
        }
        return desired;
    }

    private boolean isClearSpace(World world, Vector3d position) {
        int bx = (int) Math.floor(position.x);
        int by = (int) Math.floor(position.y);
        int bz = (int) Math.floor(position.z);
        if (!isEmptyBlock(world, bx, by, bz)) {
            return false;
        }
        return isEmptyBlock(world, bx, by + 1, bz);
    }

    private boolean isEmptyBlock(World world, int x, int y, int z) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        BlockAccessor accessor = world.getChunkIfLoaded(chunkIndex);
        if (accessor == null) {
            return false;
        }
        int blockId = accessor.getBlock(x, y, z);
        BlockType blockType = accessor.getBlockType(x, y, z);
        if (blockType == null) {
            return false;
        }
        return WorldUtil.isEmptyOnlyBlock(blockType, blockId);
    }
    private void spawnCaptureParticles(World world, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (world == null || targetRef == null || !targetRef.isValid() || config == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        position.add(0.0, 0.2, 0.0);
        String particleSystem = config.getSpawnerParticleSystem();
        if (particleSystem != null && !particleSystem.isBlank()) {
            ParticleUtil.spawnParticleEffect(particleSystem, position, store);
        }
        String soundEventId = config.getSpawnerSoundEvent();
        if (soundEventId != null && !soundEventId.isBlank()) {
            int soundIndex = SoundEvent.getAssetMap().getIndex(soundEventId);
            if (soundIndex >= 0) {
                SoundUtil.playSoundEvent3d(soundIndex, SoundCategory.SFX, position, store);
            }
        }
    }
    private boolean captureStub(Player player,
                             ItemStack itemStack,
                             Entity targetEntity,
                             ItemFeatureConfig config) {
        // Build capture metadata and apply it to the held spawner item.
        if (isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        Ref<EntityStore> targetRef = targetEntity != null ? targetEntity.getReference() : null;
        if (targetRef == null || !canCapture(player, targetRef, config)) {
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
                        + " roleId=" + config.getSpawnerRoleId()
                        + " modelAssetId=" + resolveModelAssetId(player, targetRef)
                        + " attachmentsPresent=" + (attachmentsJson != null && !attachmentsJson.isBlank())
        );
        String fullItemIcon = resolveFullItemIcon(config, attachmentsJson, itemStack.getItemId(), captureInfo.npcNameKey);

        UUID targetUuid = targetEntity.getUuid();
        UUID existingOwner = resolveOwnerFromComponent(targetRef, player.getWorld());
        if (config.isOwnerRestricted() && existingOwner != null && !existingOwner.equals(player.getUuid())) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (not owner) item=" + itemStack.getItemId()
                            + " targetUuid=" + targetUuid
            );
            return false;
        }
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

        if (!updateHeldItem(player, updated)) {
            logger.at(Level.WARNING).log("Spawner stub: failed to update held item.");
            return false;
        }
        spawnCaptureParticles(player.getWorld(), targetRef, config);
        clearOwnerIfConfigured(player, config, targetRef);
        despawnNpc(player, targetRef, targetEntity);

        logger.at(Level.FINE).log(
                "Spawner stub: capture request item=" + itemStack.getItemId()
                        + " targetUuid=" + targetEntity.getUuid()
                        + " captureClearsOwner=" + config.isCaptureClearsOwner()
        );
        return true;
    }


    
    private boolean captureStub(Player player,
                             ItemStack itemStack,
                             int targetEntityId,
                             ItemFeatureConfig config,
                             int hotbarSlot) {
        // Packet-based capture; resolve the target entity from its ID.
        if (isAlreadyCaptured(itemStack)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (item already captured) item=" + itemStack.getItemId()
            );
            return false;
        }
        Ref<EntityStore> targetRef = resolveEntityRef(player, targetEntityId, null);
        if (targetRef == null || !canCapture(player, targetRef, config)) {
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
                        + " roleId=" + config.getSpawnerRoleId()
                        + " modelAssetId=" + resolveModelAssetId(player, targetRef)
                        + " attachmentsPresent=" + (attachmentsJson != null && !attachmentsJson.isBlank())
        );
        String fullItemIcon = resolveFullItemIcon(config, attachmentsJson, itemStack.getItemId(), captureInfo.npcNameKey);

        UUID targetUuid = resolveEntityUuid(player, targetRef);
        UUID existingOwner = resolveOwnerFromComponent(targetRef, player.getWorld());
        if (config.isOwnerRestricted() && existingOwner != null && !existingOwner.equals(player.getUuid())) {
            logger.at(Level.FINE).log(
                    "Spawner stub: capture denied (not owner) item=" + itemStack.getItemId()
                            + " targetEntityId=" + targetEntityId
            );
            return false;
        }
        UUID ownerToStore = null;
        if (!config.isCaptureClearsOwner()) {
            ownerToStore = existingOwner != null ? existingOwner : player.getUuid();
        }

        ItemStack updated = swapItemId(itemStack, config.getSpawnerFilledItemId())
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.TARGET_ENTITY_ID, Codec.INTEGER, targetEntityId);
        if (attachmentsJson != null) {
            updated = updated.withMetadata(TameworkMetadataKeys.ATTACHMENTS, Codec.STRING, attachmentsJson);
        }
        boolean tamed = resolveTamedFromComponent(targetRef, player.getWorld());
        if (tamed) {
            updated = updated.withMetadata(TameworkMetadataKeys.TAMED, Codec.BOOLEAN, true);
        }
        updated = applyOwnerMetadata(updated, ownerToStore);
        updated = applyCapturedMetadata(updated, captureInfo, fullItemIcon);

        if (!updateHotbarSlot(player, hotbarSlot, updated)) {
            logger.at(Level.WARNING).log("Spawner stub: failed to update hotbar item.");
            return false;
        }
        spawnCaptureParticles(player.getWorld(), targetRef, config);
        clearOwnerIfConfigured(player, config, targetRef);
        despawnNpc(player, targetRef, null);

        logger.at(Level.FINE).log(
                "Spawner stub: capture request item=" + itemStack.getItemId()
                        + " targetEntityId=" + targetEntityId
                        + " captureClearsOwner=" + config.isCaptureClearsOwner()
        );
        return true;
    }

    // Spawns an NPC from captured item metadata, applying attachments/owner/tamed state.
    private boolean spawnFromItem(Player player,
                                  ItemStack itemStack,
                                  ItemFeatureConfig config,
                                  Integer hotbarSlot,
                                  String emptyItemIdOverride) {
        if (player == null) {
            return false;
        }
        if (!isFilledItem(itemStack, config)) {
            logger.at(Level.FINE).log(
                    "Spawner stub: spawn ignored (not filled) item=" + itemStack.getItemId()
            );
            return false;
        }

        String roleId = resolveRoleId(null, itemStack, config);
        if (roleId == null || roleId.isBlank()) {
            logger.at(Level.WARNING).log(
                    "Spawner stub: missing SpawnerRoleId for item=" + itemStack.getItemId()
            );
            return false;
        }

        World world = player.getWorld();
        if (world == null) {
            return false;
        }
        EntityStore entityStore = world.getEntityStore();
        Store<EntityStore> store = entityStore.getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }

        TransformComponent transformComponent = store.getComponent(playerRef, TransformComponent.getComponentType());
        Vector3d position = transformComponent != null
                ? new Vector3d(transformComponent.getPosition())
                : new Vector3d(0, 0, 0);
        Vector3f rotation = transformComponent != null
                ? new Vector3f(transformComponent.getRotation())
                : new Vector3f(0, 0, 0);
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        Vector3f headRot = headRotation != null ? headRotation.getRotation() : null;
        // Use head rotation so spawn offsets follow where the player is looking.
        float yaw = headRot != null ? headRot.getYaw() : rotation.getYaw();
        Vector3f forward = new Vector3f(Vector3f.FORWARD);
        forward.rotateY(yaw);
        forward.normalize();
        position.add(forward.x * SPAWN_FORWARD_DISTANCE, 0, forward.z * SPAWN_FORWARD_DISTANCE);
        position.add(0, SPAWN_OFFSET_Y, 0);
        // Nudge to a nearby safe space to avoid spawning inside blocks.
        position = resolveSafeSpawnPosition(world, position, forward);

        int roleIndex = NPCPlugin.get().getIndex(roleId);
        if (roleIndex < 0) {
            logger.at(Level.WARNING).log(
                    "Spawner stub: role lookup failed roleId=" + roleId
            );
            return false;
        }

        Pair<Ref<EntityStore>, NPCEntity> npcPair = NPCPlugin.get().spawnEntity(
                store,
                roleIndex,
                position,
                rotation,
                null,
                null
        );
        if (npcPair == null) {
            logger.at(Level.WARNING).log(
                    "Spawner stub: NPC spawn failed roleId=" + roleId
            );
            return false;
        }

        Ref<EntityStore> npcRef = npcPair.first();
        NPCEntity npc = npcPair.second();
        String attachmentsJson = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.ATTACHMENTS,
                Codec.STRING
        );
        if (attachmentsJson != null && !attachmentsJson.isBlank()) {
            logger.at(Level.FINE).log(
                    "Spawner spawn attachments: item=" + itemStack.getItemId() + " attachments=" + attachmentsJson
            );
        }
        applyAttachments(itemStack, npcRef, npc, store);
        UUID ownerUuid = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.OWNER_UUID,
                Codec.UUID_STRING
        );
        if (ownerUuid == null && config.isSpawnAssignsOwner()) {
            ownerUuid = player.getUuid();
        }
        applyOwner(config, npcRef, npc, playerRef, ownerUuid, world);
        Boolean tamedMeta = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.TAMED,
                Codec.BOOLEAN
        );
        boolean shouldBeTamed = Boolean.TRUE.equals(tamedMeta) || ownerUuid != null;
        applyTamed(npcRef, shouldBeTamed, world);
        spawnCaptureParticles(world, npcRef, config);

        ItemStack emptied = clearCapturedMetadata(itemStack);
        String emptyItemId = emptyItemIdOverride != null ? emptyItemIdOverride : resolveEmptyItemId(itemStack.getItemId());
        if (emptyItemId != null) {
            emptied = swapItemId(emptied, emptyItemId);
        }
        boolean updated = hotbarSlot != null
                ? updateHotbarSlot(player, hotbarSlot, emptied)
                : updateHeldItem(player, emptied);
        if (!updated) {
            logger.at(Level.WARNING).log("Spawner stub: failed to update item after spawn.");
        }

        logger.at(Level.FINE).log(
                "Spawner stub: spawn completed item=" + itemStack.getItemId()
                        + " roleId=" + roleId
                        + " spawnAssignsOwner=" + config.isSpawnAssignsOwner()
        );
    
        return true;
    }







    public boolean canSpawnInteraction(ItemStack itemStack,
                                       String roleIdOverride,
                                       Boolean allowUncapturedOverride) {
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        ItemFeatureConfig baseConfig = resolveConfigForItem(itemStack);
        if (baseConfig == null || !baseConfig.isSpawnerEnabled()) {
            return false;
        }
        boolean allowUncaptured = allowUncapturedOverride != null
                ? allowUncapturedOverride
                : baseConfig.isSpawnerAllowUncaptured();
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnerRoleId(resolveRoleId(roleIdOverride, itemStack, baseConfig))
                .spawnerAllowUncaptured(allowUncaptured)
                .build();
        String roleId = config.getSpawnerRoleId();
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        if (!isFilledItem(itemStack, config)) {
            return false;
        }
        int roleIndex = NPCPlugin.get().getIndex(roleId);
        return roleIndex >= 0;
    }

    public boolean canCaptureInteraction(Player player, Ref<EntityStore> targetRef, ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        if (isAlreadyCaptured(itemStack)) {
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(itemStack);
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        return canCapture(player, targetRef, config);
    }

    private boolean canCapture(Player player, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (player == null || targetRef == null || config == null) {
            return false;
        }
        if (!targetRef.isValid()) {
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
        String roleId = resolveRoleIdForCapture(npc);
        if (!isRoleAllowedForCapture(config, roleId)) {
            return false;
        }
        if (config.isRequireTamed() && !resolveTamedFromComponent(targetRef, world)) {
            return false;
        }
        UUID ownerUuid = resolveOwnerFromComponent(targetRef, world);
        if (ownerUuid != null && !ownerUuid.equals(player.getUuid()) && config.isOwnerRestricted()) {
            return false;
        }
        return true;
    }

    private String resolveRoleIdForCapture(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            String name = NPCPlugin.get().getName(roleIndex);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return null;
    }

    private boolean isRoleAllowedForCapture(ItemFeatureConfig config, String roleId) {
        if (config == null) {
            return false;
        }
        List<String> allowlist = config.getSpawnerRoleAllowlist();
        if (allowlist != null && !allowlist.isEmpty()) {
            return roleId != null && allowlist.contains(roleId);
        }
        String singleRole = config.getSpawnerRoleId();
        if (singleRole != null && !singleRole.isBlank()) {
            return roleId != null && singleRole.equals(roleId);
        }
        List<String> denylist = config.getSpawnerRoleDenylist();
        if (denylist != null && !denylist.isEmpty()) {
            return roleId == null || !denylist.contains(roleId);
        }
        return true;
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
        return config != null && config.isSpawnerAllowUncaptured();
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
            return new CaptureInfo(null, null, null, null);
        }
        World world = player.getWorld();
        if (world == null) {
            return new CaptureInfo(null, null, null, null);
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

        return new CaptureInfo(attachmentsJson, roleIndex, npcNameKey, iconPath);
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
        Integer roleIndex = captureInfo.roleIndex;
        if (roleIndex == null || roleIndex < 0) {
            return updated;
        }
        CapturedNPCMetadata meta = new CapturedNPCMetadata();
        meta.setRoleIndex(roleIndex);
        String npcNameKey = captureInfo.npcNameKey;
        if (npcNameKey != null && !npcNameKey.isBlank()) {
            meta.setNpcNameKey(npcNameKey);
        }
        String icon = (fullItemIcon != null && !fullItemIcon.isBlank()) ? fullItemIcon : captureInfo.iconPath;
        if (icon != null && !icon.isBlank()) {
            meta.setIconPath(icon);
        }
        if (fullItemIcon != null && !fullItemIcon.isBlank()) {
            meta.setFullItemIcon(fullItemIcon);
        }
        return updated.withMetadata(CapturedNPCMetadata.KEYED_CODEC, meta);
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


















