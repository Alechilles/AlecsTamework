package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.permissions.PermissionHolder;
import javax.annotation.Nullable;

/**
 * Shared permission checks for Tamework's config editor features.
 */
public final class TameworkConfigPermission {
    public static final String NODE = "tamework.config";
    private static final String[] FALLBACK_NODES = {
            "op",
            "admin",
            "operator",
            "OP",
            "Admin",
            "Operator"
    };

    private TameworkConfigPermission() {
    }

    public static boolean hasAccess(@Nullable PermissionHolder holder) {
        if (holder == null) {
            return false;
        }
        if (holder.hasPermission(NODE)) {
            return true;
        }
        for (String fallbackNode : FALLBACK_NODES) {
            if (holder.hasPermission(fallbackNode)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAccess(@Nullable Object holder) {
        return holder instanceof PermissionHolder permissionHolder && hasAccess(permissionHolder);
    }
}
