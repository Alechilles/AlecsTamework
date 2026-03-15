package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import javax.annotation.Nullable;

/**
 * Applies mounted NPC name text changes without changing component membership.
 */
final class MountedNameplateTextService {
    DisplayNameComponent createDisplayNameComponent(@Nullable String displayName) {
        return new DisplayNameComponent(Message.raw(normalize(displayName)));
    }

    void hideNameplate(@Nullable Nameplate nameplate) {
        restoreNameplate(nameplate, "");
    }

    void restoreNameplate(@Nullable Nameplate nameplate, @Nullable String displayName) {
        if (nameplate == null) {
            return;
        }
        nameplate.setText(normalize(displayName));
    }

    private String normalize(@Nullable String displayName) {
        return displayName != null ? displayName : "";
    }
}
