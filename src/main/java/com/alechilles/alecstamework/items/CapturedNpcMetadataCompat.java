package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.lang.reflect.Method;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Compatibility helpers for {@link CapturedNPCMetadata}.
 *
 * <p>Pre-release builds expose different getter/setter names for captured metadata. This class
 * centralizes reflective access so callers stay readable and can evolve independently.
 */
final class CapturedNpcMetadataCompat {
    private CapturedNpcMetadataCompat() {
    }

    @Nullable
    static CapturedNPCMetadata readMetadata(@Nullable ItemStack itemStack, @Nullable HytaleLogger logger) {
        if (itemStack == null) {
            return null;
        }
        try {
            return itemStack.getFromMetadataOrNull(CapturedNPCMetadata.KEYED_CODEC);
        } catch (Exception ex) {
            if (logger != null) {
                logger.at(Level.FINE).withCause(ex).log(
                        "Spawner stub: failed to read captured NPC metadata from item."
                );
            }
            return null;
        }
    }

    @Nullable
    static String resolveRoleId(@Nullable CapturedNPCMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        String roleId = readStringGetter(metadata,
                "getRoleId",
                "getRoleKey"
        );
        if (roleId != null && !roleId.isBlank() && !RoleNameResolver.looksLikeTranslationKey(roleId)) {
            return roleId;
        }

        String indexedRoleId = resolveRoleIdFromIndex(metadata);
        if (indexedRoleId != null && !indexedRoleId.isBlank()) {
            return indexedRoleId;
        }

        String legacyRoleId = readStringGetter(metadata,
                "getRoleName",
                "getNpcNameKey",
                "getRoleNameKey"
        );
        if (legacyRoleId == null || legacyRoleId.isBlank()) {
            return null;
        }
        if (!RoleNameResolver.looksLikeTranslationKey(legacyRoleId)) {
            return legacyRoleId;
        }
        return RoleNameResolver.extractRoleIdFromNameKey(legacyRoleId);
    }

    @Nullable
    private static String resolveRoleIdFromIndex(@Nullable CapturedNPCMetadata metadata) {
        Integer roleIndex = readIntGetter(metadata, "getRoleIndex");
        if (roleIndex == null || roleIndex < 0) {
            return null;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        String roleName = npcPlugin.getName(roleIndex);
        return (roleName == null || roleName.isBlank()) ? null : roleName;
    }

    static boolean invokeStringSetter(@Nullable Object target, @Nullable String methodName, @Nullable String value) {
        if (target == null || methodName == null || value == null || value.isBlank()) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            method.invoke(target, value);
            return true;
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    static boolean invokeIntSetter(@Nullable Object target, @Nullable String methodName, int value) {
        if (target == null || methodName == null) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, int.class);
            method.invoke(target, value);
            return true;
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    @Nullable
    private static String readStringGetter(@Nullable Object target, @Nullable String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            String value = invokeStringGetter(target, methodName);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static Integer readIntGetter(@Nullable Object target, @Nullable String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            Integer value = invokeIntGetter(target, methodName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static String invokeStringGetter(@Nullable Object target, @Nullable String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    @Nullable
    private static Integer invokeIntGetter(@Nullable Object target, @Nullable String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Integer integer) {
                return integer;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            return null;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }
}
