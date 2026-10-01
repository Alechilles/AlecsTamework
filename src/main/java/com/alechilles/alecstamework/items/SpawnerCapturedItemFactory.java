package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionGenderService;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds the filled capture item a 5.0 capture hands over (spec 12.4). It carries presentation
 * only: the filled item id, the captured flag, role, name, icon and model, the owner, and the
 * level, traits and gender its tooltip shows. The companion's state lives in the snapshot store;
 * the caller adds the identity with {@code CaptureItemKeys.write}. Call on the body's world thread.
 */
final class SpawnerCapturedItemFactory {
    private final SpawnerCaptureMetadataService captureMetadata;
    private final SpawnerItemStackMetadataService itemMetadata;
    private final SpawnerItemDisplayMetadataService displayMetadata;
    private final SpawnerNpcIdentityService npcIdentity;

    SpawnerCapturedItemFactory(@Nonnull SpawnerCaptureMetadataService captureMetadata,
                               @Nonnull SpawnerItemStackMetadataService itemMetadata,
                               @Nonnull SpawnerItemDisplayMetadataService displayMetadata,
                               @Nonnull SpawnerNpcIdentityService npcIdentity) {
        this.captureMetadata = captureMetadata;
        this.itemMetadata = itemMetadata;
        this.displayMetadata = displayMetadata;
        this.npcIdentity = npcIdentity;
    }

    @Nonnull
    ItemStack build(@Nonnull Player player, @Nonnull Ref<EntityStore> body, @Nonnull Store<EntityStore> store,
                    @Nonnull ItemStack source, @Nonnull ItemFeatureConfig config, @Nonnull String roleId,
                    @Nullable UUID owner) {
        SpawnerCaptureMetadataService.CaptureInfo info =
                captureMetadata.buildCaptureInfo(player, body, npcIdentity::resolveDisplayName);
        String fullItemIcon = captureMetadata.resolveFullItemIcon(
                config, info.attachmentsJson(), source.getItemId(), info.npcNameKey());
        ItemStack item = itemMetadata.swapItemId(source, config.getSpawnerFilledItemId())
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING, roleId);
        if (info.attachmentsJson() != null) {
            // The model's attachments pick the full item icon and the tooltip's appearance lines.
            item = item.withMetadata(TameworkMetadataKeys.ATTACHMENTS, Codec.STRING, info.attachmentsJson());
        }
        item = itemMetadata.applyOwnerMetadata(item, owner);
        item = captureMetadata.applyCaptureNameKeyMetadata(item, info);
        item = captureMetadata.applyCapturedMetadata(item, info, fullItemIcon);
        item = captureMetadata.applyCapturedModelMetadata(item, info);
        item = captureMetadata.applyCapturedNameMetadata(item, info);
        item = captureMetadata.applyTooltipDisplayNameMetadata(item, info);
        item = withTooltipProgression(item, body, store);
        return displayMetadata.applyCapturedDisplayMetadata(item, config);
    }

    /** Level, traits and gender, the progression lines the capture tooltip reads. */
    private static ItemStack withTooltipProgression(ItemStack item, Ref<EntityStore> body, Store<EntityStore> store) {
        TameworkLevelingComponent leveling = get(store, body, TameworkLevelingComponent.getComponentType());
        if (leveling != null) {
            if (leveling.getConfigId() != null && !leveling.getConfigId().isBlank()) {
                item = item.withMetadata(TameworkMetadataKeys.LEVELING_CONFIG_ID, Codec.STRING, leveling.getConfigId());
            }
            item = item.withMetadata(TameworkMetadataKeys.LEVELING_LEVEL, Codec.INTEGER, leveling.getLevel());
        }
        TameworkTraitsComponent traits = get(store, body, TameworkTraitsComponent.getComponentType());
        if (traits != null) {
            if (traits.getConfigId() != null && !traits.getConfigId().isBlank()) {
                item = item.withMetadata(TameworkMetadataKeys.TRAITS_CONFIG_ID, Codec.STRING, traits.getConfigId());
            }
            item = item.withMetadata(TameworkMetadataKeys.TRAITS_ROLL_SEED, Codec.LONG, traits.getRollSeed());
            String values = TraitValueCodec.encode(traits.getTraitValues());
            if (values != null && !values.isBlank()) {
                item = item.withMetadata(TameworkMetadataKeys.TRAITS_VALUES, Codec.STRING, values);
            }
        }
        TameworkLifeStageComponent lifeStage = get(store, body, TameworkLifeStageComponent.getComponentType());
        String gender = SpawnerNpcProgressionMetadataService.resolveCapturedGenderForMetadata(
                lifeStage, CompanionGenderService.resolveGender(body, store, null, null));
        if (gender != null && !gender.isBlank()) {
            item = item.withMetadata(TameworkMetadataKeys.LIFE_STAGE_GENDER, Codec.STRING, gender);
        }
        return item;
    }

    @Nullable
    private static <T extends Component<EntityStore>> T get(
            Store<EntityStore> store, Ref<EntityStore> ref, @Nullable ComponentType<EntityStore, T> type) {
        return type == null ? null : store.getComponent(ref, type);
    }
}
