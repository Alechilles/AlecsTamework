package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;

/**
 * Chooses the concrete role that owns a native mount before its NPC is switched to Empty_Role.
 */
public record NativeMountSourceRole(@Nonnull String id, int index) {
    public static NativeMountSourceRole resolve(String liveRoleId,
                                                 int liveRoleIndex,
                                                 String actionRoleId,
                                                 int actionRoleIndex) {
        if (isUsable(liveRoleId, liveRoleIndex)) {
            return new NativeMountSourceRole(liveRoleId.trim(), liveRoleIndex);
        }
        return new NativeMountSourceRole(
                actionRoleId == null ? "" : actionRoleId.trim(), actionRoleIndex);
    }

    private static boolean isUsable(String roleId, int roleIndex) {
        return roleId != null && !roleId.isBlank() && roleIndex >= 0;
    }
}
