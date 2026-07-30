package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import javax.annotation.Nullable;

/** Resolves a native mount's persisted source role through the active NPC registry. */
final class NativeMountedRoleLookup {
    @Nullable
    private final NPCPlugin npcPlugin;

    NativeMountedRoleLookup(@Nullable NPCPlugin npcPlugin) {
        this.npcPlugin = npcPlugin;
    }

    @Nullable
    String resolve(@Nullable NPCMountComponent mount) {
        if (mount == null || npcPlugin == null) {
            return null;
        }
        return npcPlugin.getName(mount.getOriginalRoleIndex());
    }
}
