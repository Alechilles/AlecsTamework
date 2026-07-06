package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.Cosmetic;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts the player's current visible equipment into rider attachments and cosmetic visibility masks.
 */
public final class AvatarFlightEquipmentAttachmentResolver {
    private AvatarFlightEquipmentAttachmentResolver() {
    }

    @Nonnull
    public static ModelAttachment[] resolve(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull ComponentAccessor<EntityStore> accessor) {
        return resolveSnapshot(ref, accessor).attachments();
    }

    @Nonnull
    public static EquipmentSnapshot resolveSnapshot(@Nonnull Ref<EntityStore> ref,
                                                    @Nonnull ComponentAccessor<EntityStore> accessor) {
        EquipmentUpdate update = AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate(ref, accessor);
        ArrayList<ModelAttachment> attachments = new ArrayList<>();
        EnumSet<Cosmetic> hiddenCosmetics = EnumSet.noneOf(Cosmetic.class);
        if (update.armorIds != null) {
            for (String armorId : update.armorIds) {
                appendItemAttachment(attachments, hiddenCosmetics, armorId);
            }
        }
        return new EquipmentSnapshot(
                attachments.toArray(ModelAttachment[]::new),
                Collections.unmodifiableSet(hiddenCosmetics)
        );
    }

    private static void appendItemAttachment(@Nonnull ArrayList<ModelAttachment> attachments,
                                             @Nonnull EnumSet<Cosmetic> hiddenCosmetics,
                                             @Nullable String itemId) {
        if (itemId == null || itemId.isBlank() || BlockType.EMPTY_KEY.equals(itemId)) {
            return;
        }
        Item item = Item.getAssetMap().getAsset(itemId);
        if (item == null) {
            return;
        }
        collectHiddenCosmetics(hiddenCosmetics, item);
        String model = item.getModel();
        String texture = item.getTexture();
        if (model == null || model.isBlank() || texture == null || texture.isBlank()) {
            return;
        }
        attachments.add(new ModelAttachment(
                AvatarFlightEquipmentModelVariantService.resolveForRider(model),
                texture,
                null,
                null,
                1.0
        ));
    }

    private static void collectHiddenCosmetics(@Nonnull EnumSet<Cosmetic> hiddenCosmetics,
                                               @Nonnull Item item) {
        if (item.getArmor() == null) {
            return;
        }
        com.hypixel.hytale.protocol.ItemArmor armor = item.getArmor().toPacket();
        if (armor.cosmeticsToHide == null) {
            return;
        }
        for (Cosmetic cosmetic : armor.cosmeticsToHide) {
            if (cosmetic != null) {
                hiddenCosmetics.add(cosmetic);
            }
        }
    }

    public record EquipmentSnapshot(@Nonnull ModelAttachment[] attachments,
                                    @Nonnull Set<Cosmetic> hiddenCosmetics) {
    }
}
