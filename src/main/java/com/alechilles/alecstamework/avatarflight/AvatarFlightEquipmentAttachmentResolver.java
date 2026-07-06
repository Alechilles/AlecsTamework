package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts the player's current visible equipment IDs into rider model attachments.
 */
public final class AvatarFlightEquipmentAttachmentResolver {
    private AvatarFlightEquipmentAttachmentResolver() {
    }

    @Nonnull
    public static ModelAttachment[] resolve(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull ComponentAccessor<EntityStore> accessor) {
        EquipmentUpdate update = AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate(ref, accessor);
        ArrayList<ModelAttachment> attachments = new ArrayList<>();
        appendItemAttachment(attachments, update.rightHandItemId);
        appendItemAttachment(attachments, update.leftHandItemId);
        if (update.armorIds != null) {
            for (String armorId : update.armorIds) {
                appendItemAttachment(attachments, armorId);
            }
        }
        return attachments.toArray(ModelAttachment[]::new);
    }

    private static void appendItemAttachment(@Nonnull ArrayList<ModelAttachment> attachments,
                                             @Nullable String itemId) {
        if (itemId == null || itemId.isBlank() || BlockType.EMPTY_KEY.equals(itemId)) {
            return;
        }
        Item item = Item.getAssetMap().getAsset(itemId);
        if (item == null) {
            return;
        }
        String model = item.getModel();
        String texture = item.getTexture();
        if (model == null || model.isBlank() || texture == null || texture.isBlank()) {
            return;
        }
        attachments.add(new ModelAttachment(model, texture, null, null, 1.0));
    }
}
