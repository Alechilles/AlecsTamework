package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

public final class ActionTameworkDenyCaptureUntamed extends TameworkActionBase {
    private final String[] foodItemIds;

    public ActionTameworkDenyCaptureUntamed(BuilderActionTameworkDenyCaptureUntamed builder, BuilderSupport support) {
        super(builder);
        this.foodItemIds = builder.getFoodItemIds(support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return resolveInteractionPlayer(role, infoProvider, store) != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        String npcName = resolveNpcName(resolveNpcEntity(npcRef, store));
        String foodList = resolveFoodList(player);
        if (foodList != null && !foodList.isBlank()) {
            OwnerMessageUtil.sendUntamed(player, npcName, foodList);
        } else {
            OwnerMessageUtil.sendUntamed(player, npcName);
        }
        return true;
    }

    private String resolveFoodList(Player player) {
        if (foodItemIds == null || foodItemIds.length == 0) {
            return null;
        }
        Tamework instance = Tamework.getInstance();
        var registry = instance != null ? instance.getTranslationRegistry() : null;
        I18nModule i18n = I18nModule.get();
        String language = null;
        if (player != null && player.getPlayerRef() != null) {
            language = player.getPlayerRef().getLanguage();
        }
        if (language == null || language.isBlank()) {
            language = "en-US";
        }
        StringBuilder builder = new StringBuilder();
        for (String itemId : foodItemIds) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            String display = null;
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null) {
                String key = item.getTranslationKey();
                if (i18n != null) {
                    display = i18n.getMessage(language, key);
                }
                if (display == null || display.isBlank()) {
                    display = key;
                }
            }
            if (display == null || display.isBlank()) {
                if (registry != null) {
                    String key = "items." + itemId + ".name";
                    display = registry.get(key);
                    if (display == null || display.isBlank()) {
                        display = registry.get(itemId);
                    }
                }
            }
            if (display == null || display.isBlank()) {
                display = itemId;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(display);
        }
        return builder.length() > 0 ? builder.toString() : null;
    }
}
