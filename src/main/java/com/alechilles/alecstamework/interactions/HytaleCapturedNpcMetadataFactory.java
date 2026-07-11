package com.alechilles.alecstamework.interactions;

import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds the same Hytale 0.5.6 CapturedNPCMetadata fields used by vanilla capture crates. */
final class HytaleCapturedNpcMetadataFactory {
    @Nullable
    CapturedNPCMetadata create(@Nonnull NPCEntity npc,
                               @Nonnull PersistentModel persistentModel,
                               @Nullable String fallbackFullIcon) {
        if (npc.getRole() == null) {
            return null;
        }
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        ModelAsset model = ModelAsset.getAssetMap().getAsset(
                persistentModel.getModelReference().getModelAssetId());
        if (model != null) {
            metadata.setIconPath(model.getIcon());
        }
        NPCPlugin plugin = NPCPlugin.get();
        String roleName = plugin != null ? plugin.getName(npc.getRoleIndex()) : null;
        if (roleName == null || roleName.isBlank()) {
            return null;
        }
        metadata.setNpcNameKey(roleName);
        ModelAsset appearance = ModelAsset.getAssetMap().getAsset(
                npc.getRole().getAppearanceName());
        String appearanceIcon = appearance != null ? appearance.getIcon() : null;
        metadata.setFullItemIcon(appearanceIcon != null ? appearanceIcon : fallbackFullIcon);
        metadata.setAlarmStore(npc.getAlarmStore());
        return metadata;
    }
}
