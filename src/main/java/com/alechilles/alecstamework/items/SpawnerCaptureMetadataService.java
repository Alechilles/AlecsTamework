package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import org.bson.BsonDocument;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Builds and writes captured-NPC item metadata payloads for spawner items.
 */
final class SpawnerCaptureMetadataService {
    private static final String GENERIC_CAPTURE_CRATE_NAME = "Capture Crate";
    private static final String GENERIC_CAPTURE_CRATE_KEY = "server.items.captureCrate.name";

    interface NpcDisplayNameResolver {
        @Nullable
        String resolve(@Nullable Ref<EntityStore> targetRef, @Nullable Store<EntityStore> store, @Nullable NPCEntity npc);
    }

    static final class CapturedName {
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

        @Nullable
        String name() {
            return name;
        }
    }

    static final class CaptureInfo {
        private final String attachmentsJson;
        private final String modelId;
        private final Integer roleIndex;
        private final String roleId;
        private final String roleNameKey;
        private final String iconPath;
        private final CapturedName capturedName;
        private final String tooltipDisplayName;

        private CaptureInfo(String attachmentsJson,
                            String modelId,
                            Integer roleIndex,
                            String roleId,
                            String roleNameKey,
                            String iconPath,
                            CapturedName capturedName,
                            String tooltipDisplayName) {
            this.attachmentsJson = attachmentsJson;
            this.modelId = modelId;
            this.roleIndex = roleIndex;
            this.roleId = roleId;
            this.roleNameKey = roleNameKey;
            this.iconPath = iconPath;
            this.capturedName = capturedName;
            this.tooltipDisplayName = tooltipDisplayName;
        }

        @Nullable
        String attachmentsJson() {
            return attachmentsJson;
        }

        @Nullable
        String modelId() {
            return modelId;
        }

        @Nullable
        String npcNameKey() {
            return roleId;
        }

        @Nullable
        String roleNameKey() {
            return roleNameKey;
        }

        @Nullable
        CapturedName capturedName() {
            return capturedName;
        }

        @Nullable
        String tooltipDisplayName() {
            return tooltipDisplayName;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Type ATTACHMENT_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final HytaleLogger logger;
    private final ItemFeatureRegistry registry;

    SpawnerCaptureMetadataService(HytaleLogger logger, ItemFeatureRegistry registry) {
        this.logger = logger;
        this.registry = registry;
    }

    CaptureInfo buildCaptureInfo(@Nullable Player player,
                                 @Nullable Ref<EntityStore> targetRef,
                                 @Nullable NpcDisplayNameResolver displayNameResolver) {
        if (player == null || targetRef == null || !targetRef.isValid()) {
            return new CaptureInfo(null, null, null, null, null, null, null, null);
        }
        World world = player.getWorld();
        if (world == null) {
            return new CaptureInfo(null, null, null, null, null, null, null, null);
        }
        Store<EntityStore> store = world.getEntityStore().getStore();

        String attachmentsJson = null;
        String modelId = null;
        String iconPath = null;
        ModelComponent modelComponent = store.getComponent(targetRef, ModelComponent.getComponentType());
        if (modelComponent != null) {
            Model model = modelComponent.getModel();
            if (model != null) {
                modelId = model.getModelAssetId();
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
        String roleId = null;
        String roleNameKey = null;
        String tooltipDisplayName = null;
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            int resolvedRoleIndex = npc.getRoleIndex();
            if (resolvedRoleIndex >= 0) {
                roleIndex = resolvedRoleIndex;
                String nameKey = NPCPlugin.get().getName(resolvedRoleIndex);
                if (nameKey != null && !nameKey.isBlank()) {
                    roleId = nameKey;
                }
            }
            roleNameKey = firstNonBlank(
                    RoleNameResolver.resolveRoleNameKey(npc.getRole()),
                    RoleNameResolver.defaultRoleNameKey(roleId)
            );
            if (displayNameResolver != null) {
                tooltipDisplayName = displayNameResolver.resolve(targetRef, store, npc);
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
        if (capturedName != null && capturedName.name != null && !capturedName.name.isBlank()) {
            tooltipDisplayName = capturedName.name;
        } else {
            tooltipDisplayName = sanitizeTooltipDisplayName(tooltipDisplayName, roleId, roleNameKey);
        }
        return new CaptureInfo(attachmentsJson, modelId, roleIndex, roleId, roleNameKey, iconPath, capturedName, tooltipDisplayName);
    }

    @Nullable
    String resolveFullItemIcon(@Nullable ItemFeatureConfig config,
                               @Nullable String attachmentsJson,
                               @Nullable String itemId,
                               @Nullable String roleId) {
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
        ItemFeatureConfig.SpawnerIconOverrideGroup roleGroup = firstIconOverrideGroupForRole(
                resolved.getSpawnerIconOverrideGroups(),
                roleId
        );
        List<ItemFeatureConfig.SpawnerIconOverride> groupOverrides =
                roleGroup != null ? roleGroup.getOverrides() : null;
        String groupDefaultIcon = roleGroup != null ? roleGroup.getIconDefault() : null;
        List<ItemFeatureConfig.SpawnerIconOverride> overrides = resolved.getSpawnerIconOverrides();
        boolean hasRoleOverrides = roleOverrides != null && !roleOverrides.isEmpty();
        boolean hasGroupOverrides = groupOverrides != null && !groupOverrides.isEmpty();
        boolean hasGlobalOverrides = overrides != null && !overrides.isEmpty();
        boolean hasGroupDefaultIcon = groupDefaultIcon != null && !groupDefaultIcon.isBlank();
        if (!hasRoleOverrides && !hasGroupOverrides && !hasGlobalOverrides) {
            return hasGroupDefaultIcon ? groupDefaultIcon : defaultIcon;
        }
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return hasGroupDefaultIcon ? groupDefaultIcon : defaultIcon;
        }

        Map<String, String> attachments;
        try {
            attachments = GSON.fromJson(attachmentsJson, ATTACHMENT_MAP_TYPE);
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log("Spawner icon override: failed to parse attachments.");
            }
            return hasGroupDefaultIcon ? groupDefaultIcon : defaultIcon;
        }
        if (attachments == null) {
            return hasGroupDefaultIcon ? groupDefaultIcon : defaultIcon;
        }

        if (hasRoleOverrides) {
            for (ItemFeatureConfig.SpawnerIconOverride override : roleOverrides) {
                if (override == null) {
                    continue;
                }
                if (matchesAttachments(override.getAttachments(), attachments)) {
                    String icon = override.getIcon();
                    if (logger != null) {
                        logger.at(Level.FINE).log(
                                "Spawner icon override (role): matched item=" + itemId
                                        + " role=" + roleId
                                        + " icon=" + icon
                                        + " attachments=" + attachmentsJson
                        );
                    }
                    return icon;
                }
            }
        }

        if (hasGroupOverrides) {
            for (ItemFeatureConfig.SpawnerIconOverride override : groupOverrides) {
                if (override == null) {
                    continue;
                }
                if (matchesAttachments(override.getAttachments(), attachments)) {
                    String icon = override.getIcon();
                    if (logger != null) {
                        logger.at(Level.FINE).log(
                                "Spawner icon override (group): matched item=" + itemId
                                        + " role=" + roleId
                                        + " icon=" + icon
                                        + " attachments=" + attachmentsJson
                        );
                    }
                    return icon;
                }
            }
        }

        if (hasGroupDefaultIcon) {
            if (logger != null) {
                logger.at(Level.FINE).log(
                        "Spawner icon override (group default): matched item=" + itemId
                                + " role=" + roleId
                                + " icon=" + groupDefaultIcon
                                + " attachments=" + attachmentsJson
                );
            }
            return groupDefaultIcon;
        }

        if (hasGlobalOverrides) {
            for (ItemFeatureConfig.SpawnerIconOverride override : overrides) {
                if (override == null) {
                    continue;
                }
                if (matchesAttachments(override.getAttachments(), attachments)) {
                    String icon = override.getIcon();
                    if (logger != null) {
                        logger.at(Level.FINE).log(
                                "Spawner icon override: matched item=" + itemId + " icon=" + icon + " attachments=" + attachmentsJson
                        );
                    }
                    return icon;
                }
            }
        }

        if (logger != null) {
            logger.at(Level.FINE).log(
                    "Spawner icon override: no match item=" + itemId + " role=" + roleId + " attachments=" + attachmentsJson
            );
        }
        return defaultIcon;
    }

    @Nullable
    ItemStack applyCapturedMetadata(@Nullable ItemStack updated, @Nullable CaptureInfo captureInfo, @Nullable String fullItemIcon) {
        if (updated == null || captureInfo == null) {
            return updated;
        }
        CapturedNPCMetadata meta = new CapturedNPCMetadata();
        boolean wrote = false;

        Integer roleIndex = captureInfo.roleIndex;
        if (roleIndex != null && roleIndex >= 0) {
            wrote |= CapturedNpcMetadataCompat.invokeIntSetter(meta, "setRoleIndex", roleIndex);
        }
        String roleId = captureInfo.roleId;
        String roleNameKey = captureInfo.roleNameKey;
        String tooltipName = captureInfo.tooltipDisplayName;
        if (tooltipName != null) {
            wrote |= CapturedNpcMetadataCompat.invokeStringSetter(meta, "setNpcNameKey", tooltipName);
        }
        if (roleNameKey != null && !roleNameKey.isBlank()) {
            wrote |= CapturedNpcMetadataCompat.invokeStringSetter(meta, "setRoleNameKey", roleNameKey);
        }
        if (roleId != null && !roleId.isBlank()) {
            wrote |= CapturedNpcMetadataCompat.invokeStringSetter(meta, "setRoleId", roleId);
            wrote |= CapturedNpcMetadataCompat.invokeStringSetter(meta, "setRoleKey", roleId);
        }
        String icon = (fullItemIcon != null && !fullItemIcon.isBlank()) ? fullItemIcon : captureInfo.iconPath;
        if (icon != null && !icon.isBlank()) {
            wrote |= CapturedNpcMetadataCompat.invokeStringSetter(meta, "setIconPath", icon);
        }
        if (fullItemIcon != null && !fullItemIcon.isBlank()) {
            wrote |= CapturedNpcMetadataCompat.invokeStringSetter(meta, "setFullItemIcon", fullItemIcon);
        }
        if (!wrote) {
            return updated;
        }
        return updated.withMetadata(CapturedNPCMetadata.KEYED_CODEC, meta);
    }

    @Nullable
    ItemStack applyCapturedModelMetadata(@Nullable ItemStack updated, @Nullable CaptureInfo captureInfo) {
        if (updated == null || captureInfo == null || captureInfo.modelId == null || captureInfo.modelId.isBlank()) {
            return updated == null ? null : clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_MODEL_ID);
        }
        return updated.withMetadata(TameworkMetadataKeys.CAPTURE_MODEL_ID, Codec.STRING, captureInfo.modelId);
    }

    @Nullable
    ItemStack applyCapturedNameMetadata(@Nullable ItemStack updated, @Nullable CaptureInfo captureInfo) {
        if (updated == null || captureInfo == null) {
            return updated;
        }
        CapturedName capturedName = captureInfo.capturedName;
        if (capturedName == null || capturedName.name == null || capturedName.name.isBlank()) {
            return clearNameMetadata(updated);
        }
        ItemStack result = updated.withMetadata(TameworkMetadataKeys.NPC_NAME, Codec.STRING, capturedName.name);
        if (capturedName.ownerId != null) {
            result = result.withMetadata(TameworkMetadataKeys.NPC_NAME_OWNER_UUID, Codec.UUID_STRING, capturedName.ownerId);
        } else {
            result = clearMetadataKey(result, TameworkMetadataKeys.NPC_NAME_OWNER_UUID);
        }
        long updatedMs = capturedName.updatedMs > 0 ? capturedName.updatedMs : System.currentTimeMillis();
        result = result.withMetadata(TameworkMetadataKeys.NPC_NAME_UPDATED_MS, Codec.LONG, updatedMs);
        if (capturedName.source != null) {
            result = result.withMetadata(TameworkMetadataKeys.NPC_NAME_SOURCE, Codec.STRING, capturedName.source.name());
        } else {
            result = clearMetadataKey(result, TameworkMetadataKeys.NPC_NAME_SOURCE);
        }
        return result;
    }

    @Nullable
    ItemStack applyTooltipDisplayNameMetadata(@Nullable ItemStack updated, @Nullable CaptureInfo captureInfo) {
        if (updated == null || captureInfo == null) {
            return updated;
        }
        if (captureInfo.tooltipDisplayName == null) {
            return clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME);
        }
        return updated.withMetadata(
                TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME,
                Codec.STRING,
                captureInfo.tooltipDisplayName
        );
    }

    @Nullable
    ItemStack applyCaptureNameKeyMetadata(@Nullable ItemStack updated, @Nullable CaptureInfo captureInfo) {
        if (updated == null || captureInfo == null) {
            return updated;
        }
        if (captureInfo.roleNameKey == null || captureInfo.roleNameKey.isBlank()) {
            return clearMetadataKey(updated, TameworkMetadataKeys.CAPTURE_NAME_KEY);
        }
        return updated.withMetadata(TameworkMetadataKeys.CAPTURE_NAME_KEY, Codec.STRING, captureInfo.roleNameKey);
    }

    @Nullable
    ItemStack clearNameMetadata(@Nullable ItemStack updated) {
        ItemStack cleared = clearMetadataKey(updated, TameworkMetadataKeys.NPC_NAME);
        cleared = clearMetadataKey(cleared, TameworkMetadataKeys.NPC_NAME_OWNER_UUID);
        cleared = clearMetadataKey(cleared, TameworkMetadataKeys.NPC_NAME_UPDATED_MS);
        cleared = clearMetadataKey(cleared, TameworkMetadataKeys.NPC_NAME_SOURCE);
        return cleared;
    }

    private ItemFeatureConfig resolveIconConfig(@Nullable ItemFeatureConfig config) {
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

    @Nullable
    private ItemFeatureConfig.SpawnerIconOverrideGroup firstIconOverrideGroupForRole(
            @Nullable List<ItemFeatureConfig.SpawnerIconOverrideGroup> groups,
            @Nullable String roleId) {
        if (roleId == null || roleId.isBlank() || groups == null || groups.isEmpty()) {
            return null;
        }
        for (ItemFeatureConfig.SpawnerIconOverrideGroup group : groups) {
            if (group == null || group.getRoles().isEmpty()) {
                continue;
            }
            if (group.getRoles().contains(roleId)) {
                return group;
            }
        }
        return null;
    }

    private boolean matchesAttachments(@Nullable Map<String, String> required, @Nullable Map<String, String> actual) {
        if (required == null || required.isEmpty() || actual == null || actual.isEmpty()) {
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

    @Nullable
    private static String sanitizeTooltipDisplayName(@Nullable String candidate,
                                                     @Nullable String roleId,
                                                     @Nullable String roleNameKey) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.equalsIgnoreCase(GENERIC_CAPTURE_CRATE_NAME) || trimmed.equalsIgnoreCase(GENERIC_CAPTURE_CRATE_KEY)) {
            return null;
        }
        if (roleId != null && !roleId.isBlank() && trimmed.equalsIgnoreCase(roleId)) {
            return null;
        }
        if (roleNameKey != null && !roleNameKey.isBlank() && trimmed.equalsIgnoreCase(roleNameKey)) {
            return null;
        }
        return trimmed;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    @Nullable
    private ItemStack clearMetadataKey(@Nullable ItemStack stack, @Nullable String key) {
        if (stack == null || key == null) {
            return stack;
        }
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(key)) {
            return stack;
        }
        BsonDocument copied = metadata.clone();
        copied.remove(key);
        return new ItemStack(
                stack.getItemId(),
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                copied
        );
    }
}
