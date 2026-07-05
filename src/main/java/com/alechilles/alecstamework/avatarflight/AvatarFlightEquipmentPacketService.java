package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds packet-only avatar-flight equipment visuals without mutating player inventory.
 */
public final class AvatarFlightEquipmentPacketService {
    private AvatarFlightEquipmentPacketService() {
    }

    @Nonnull
    public static EquipmentUpdate createCurrentEquipmentUpdate(@Nonnull Ref<EntityStore> ref,
                                                               @Nonnull ComponentAccessor<EntityStore> accessor) {
        PlayerSettings playerSettings = accessor.getComponent(ref, PlayerSettings.getComponentType());
        InventoryComponent.Armor armor = accessor.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utility = accessor.getComponent(ref, InventoryComponent.Utility.getComponentType());
        return InventoryUtils.createEquipmentUpdate(ref, accessor, playerSettings, armor, utility);
    }

    @Nonnull
    public static EquipmentUpdate createHiddenOwnerEquipmentUpdate(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings) {
        EquipmentUpdate update = createCurrentEquipmentUpdate(ref, accessor);
        if (!settings.isHideOwnerEquipment()) {
            return update;
        }
        if (settings.isHideOwnerHands()) {
            update.rightHandItemId = BlockType.EMPTY_KEY;
            update.leftHandItemId = BlockType.EMPTY_KEY;
        }
        if (settings.isHideOwnerArmor() && update.armorIds != null) {
            Arrays.fill(update.armorIds, "");
        }
        return update;
    }

    @Nonnull
    public static String equipmentSignature(@Nonnull EquipmentUpdate update) {
        String armor = update.armorIds == null ? "" : String.join(",", update.armorIds);
        return safe(update.rightHandItemId) + "|" + safe(update.leftHandItemId) + "|" + armor;
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
