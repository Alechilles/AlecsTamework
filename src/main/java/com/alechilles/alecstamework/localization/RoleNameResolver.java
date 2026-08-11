package com.alechilles.alecstamework.localization;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Resolves NPC role display-name keys across base-game and mod role assets.
 */
public final class RoleNameResolver {
    private static final String[] ROLE_NAME_PARAM_KEYS = {
            "NameTranslationKey",
            "RoleNameTranslationKey",
            "NameKey",
            "RoleNameKey",
            "NpcNameKey"
    };
    private static final String[] ROLE_KEY_PREFIXES = {
            "server.npcRole.",
            "npcRole.",
            "server.npcRoles.",
            "npcRoles."
    };

    private RoleNameResolver() {
    }

    @FunctionalInterface
    public interface TranslationLookup {
        @Nullable
        String get(@Nullable String key);
    }

    @Nullable
    public static String resolveRoleNameKey(@Nullable Role role) {
        return resolveRoleNameKey(role, null, null);
    }

    /**
     * Resolves a role name key with the live NPC context available to Update 6 ECS support access.
     */
    @Nullable
    public static String resolveRoleNameKey(@Nullable Role role,
                                            @Nullable Ref<EntityStore> npcRef,
                                            @Nullable Store<EntityStore> store) {
        if (role == null) {
            return null;
        }
        String nameTranslationKey = role.getNameTranslationKey();
        if (nameTranslationKey != null && !nameTranslationKey.isBlank()) {
            return nameTranslationKey;
        }
        EntitySupport entitySupport = NpcSupportAccess.entity(role, npcRef, store);
        if (entitySupport != null) {
            String scopedKey = readScopeStringParam(entitySupport.getSensorScope(), ROLE_NAME_PARAM_KEYS);
            if (scopedKey != null && !scopedKey.isBlank()) {
                return scopedKey;
            }
        }
        return resolveRoleNameKey(role.getRoleName());
    }

    @Nullable
    public static String resolveRoleNameKey(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        NPCPlugin npcPlugin = getNpcPluginOrNull();
        if (npcPlugin == null) {
            return null;
        }
        int roleIndex = npcPlugin.getIndex(roleId.trim());
        return resolveRoleNameKey(roleIndex, npcPlugin);
    }

    @Nullable
    public static String resolveRoleNameKey(int roleIndex) {
        return resolveRoleNameKey(roleIndex, getNpcPluginOrNull());
    }

    @Nullable
    private static String resolveRoleNameKey(int roleIndex, @Nullable NPCPlugin npcPlugin) {
        if (npcPlugin == null || roleIndex < 0) {
            return null;
        }
        Builder<Role> roleBuilder = npcPlugin.tryGetCachedValidRole(roleIndex);
        if (roleBuilder == null) {
            return null;
        }
        BuilderParameters builderParameters = roleBuilder.getBuilderParameters();
        if (builderParameters == null) {
            return null;
        }
        try {
            StdScope scope = builderParameters.createScope();
            return readScopeStringParam(scope, ROLE_NAME_PARAM_KEYS);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static NPCPlugin getNpcPluginOrNull() {
        try {
            return NPCPlugin.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static String resolveDisplayName(@Nullable String roleId,
                                            @Nullable String roleNameKey,
                                            @Nullable TranslationLookup lookup) {
        String translated = translateNameKey(lookup, roleNameKey);
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        translated = translateNameKey(lookup, roleId);
        if (translated != null && !translated.isBlank()) {
            return translated;
        }
        String resolvedRoleId = firstNonBlank(extractRoleIdFromNameKey(roleNameKey), roleId);
        return firstNonBlank(resolvedRoleId, roleNameKey);
    }

    @Nullable
    public static String translateNameKey(@Nullable TranslationLookup lookup, @Nullable String nameKey) {
        if (lookup == null || nameKey == null || nameKey.isBlank()) {
            return null;
        }
        for (String candidate : buildNameKeyCandidates(nameKey)) {
            String translated = lookup.get(candidate);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return null;
    }

    public static List<String> buildNameKeyCandidates(@Nullable String nameKey) {
        ArrayList<String> candidates = new ArrayList<>(8);
        if (nameKey == null || nameKey.isBlank()) {
            return candidates;
        }
        String trimmed = nameKey.trim();
        addCandidate(candidates, trimmed);
        if (!trimmed.contains(".")) {
            addRoleIdNameCandidates(candidates, trimmed);
            addTamedBaseRoleNameCandidates(candidates, trimmed);
            addNormalizedRoleNameCandidates(candidates, trimmed);
            return candidates;
        }
        if (trimmed.startsWith("server.")) {
            addCandidate(candidates, trimmed.substring("server.".length()));
        } else {
            addCandidate(candidates, "server." + trimmed);
        }
        addServerVariant(candidates, toSingularNpcRoleKey(trimmed));
        addServerVariant(candidates, toPluralNpcRoleKey(trimmed));
        addTitleNameVariants(candidates);
        String embeddedRoleId = extractRoleIdFromNameKey(trimmed);
        if (embeddedRoleId != null) {
            addRoleIdNameCandidates(candidates, embeddedRoleId);
            addTamedBaseRoleNameCandidates(candidates, embeddedRoleId);
            addNormalizedRoleNameCandidates(candidates, embeddedRoleId);
        }
        return candidates;
    }

    @Nullable
    public static String extractRoleIdFromNameKey(@Nullable String nameKey) {
        if (nameKey == null || nameKey.isBlank()) {
            return null;
        }
        String trimmed = nameKey.trim();
        for (String prefix : ROLE_KEY_PREFIXES) {
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String remainder = trimmed.substring(prefix.length());
            if (remainder.endsWith(".name")) {
                remainder = remainder.substring(0, remainder.length() - ".name".length());
            }
            return remainder.isBlank() ? null : remainder;
        }
        return null;
    }

    @Nullable
    public static String defaultRoleNameKey(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return "server.npcRoles." + roleId.trim() + ".name";
    }

    public static boolean looksLikeTranslationKey(@Nullable String value) {
        return value != null && !value.isBlank() && value.indexOf('.') >= 0;
    }

    /**
     * Returns whether a stored display value is only another spelling of the
     * role identity rather than a player-assigned NPC name.
     */
    public static boolean isRoleIdentityDisplayName(
            @Nullable String displayName,
            @Nullable String roleId,
            @Nullable String roleNameKey
    ) {
        if (displayName == null || displayName.isBlank()) {
            return false;
        }
        String candidate = displayName.trim();
        if (equalsIgnoreCase(candidate, roleId)
                || equalsIgnoreCase(candidate, roleNameKey)
                || equalsIgnoreCase(
                candidate,
                extractRoleIdFromNameKey(roleNameKey)
        )) {
            return true;
        }
        if (roleId == null || roleId.isBlank()
                || !roleId.regionMatches(
                true, 0, "Tamed_", 0, "Tamed_".length()
        )) {
            return false;
        }
        return equalsIgnoreCase(
                candidate,
                roleId.trim().substring("Tamed_".length())
        );
    }

    private static void addCandidate(List<String> candidates, @Nullable String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!candidates.contains(key)) {
            candidates.add(key);
        }
    }

    private static boolean equalsIgnoreCase(
            String candidate,
            @Nullable String expected
    ) {
        return expected != null
                && !expected.isBlank()
                && candidate.equalsIgnoreCase(expected.trim());
    }

    private static void addRoleIdNameCandidates(List<String> candidates, @Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        addCandidate(candidates, "npcRole." + roleId + ".name");
        addCandidate(candidates, "server.npcRole." + roleId + ".name");
        addCandidate(candidates, "npcRoles." + roleId + ".name");
        addCandidate(candidates, "server.npcRoles." + roleId + ".name");
    }

    private static void addTamedBaseRoleNameCandidates(List<String> candidates, @Nullable String roleId) {
        if (roleId == null || roleId.isBlank()
                || !roleId.regionMatches(true, 0, "Tamed_", 0, "Tamed_".length())) {
            return;
        }
        String baseRoleId = roleId.substring("Tamed_".length());
        addRoleIdNameCandidates(candidates, baseRoleId);
    }

    private static void addNormalizedRoleNameCandidates(List<String> candidates,
                                                        @Nullable String roleId) {
        String normalized = normalizeRoleIdCase(roleId);
        if (normalized == null || normalized.equals(roleId)) {
            return;
        }
        addRoleIdNameCandidates(candidates, normalized);
        addTamedBaseRoleNameCandidates(candidates, normalized);
    }

    @Nullable
    private static String normalizeRoleIdCase(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(roleId.length());
        for (String part : roleId.trim().split("_", -1)) {
            if (part.isEmpty()) {
                if (!normalized.isEmpty()) {
                    normalized.append('_');
                }
                continue;
            }
            if (!normalized.isEmpty()) {
                normalized.append('_');
            }
            normalized.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                normalized.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return normalized.isEmpty() ? null : normalized.toString();
    }

    private static void addServerVariant(List<String> candidates, @Nullable String key) {
        addCandidate(candidates, key);
        if (key == null || key.isBlank()) {
            return;
        }
        if (key.startsWith("server.")) {
            addCandidate(candidates, key.substring("server.".length()));
        } else {
            addCandidate(candidates, "server." + key);
        }
    }

    private static void addTitleNameVariants(List<String> candidates) {
        ArrayList<String> snapshot = new ArrayList<>(candidates);
        for (String candidate : snapshot) {
            if (candidate != null && candidate.endsWith(".title")) {
                addCandidate(candidates, candidate.substring(0, candidate.length() - ".title".length()) + ".name");
            }
        }
    }

    private static String toSingularNpcRoleKey(String key) {
        return key
                .replace("server.npcRoles.", "server.npcRole.")
                .replace("npcRoles.", "npcRole.");
    }

    private static String toPluralNpcRoleKey(String key) {
        return key
                .replace("server.npcRole.", "server.npcRoles.")
                .replace("npcRole.", "npcRoles.");
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    @Nullable
    private static String readScopeStringParam(@Nullable Object scope, @Nullable String... paramNames) {
        if (scope == null || paramNames == null || paramNames.length == 0) {
            return null;
        }
        for (String paramName : paramNames) {
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            try {
                Method method = scope.getClass().getMethod("getStringParamOrNull", String.class);
                Object value = method.invoke(scope, paramName);
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Method method = scope.getClass().getMethod("getStringSupplier", String.class);
                Object value = method.invoke(scope, paramName);
                if (value instanceof Supplier<?> supplier) {
                    Object supplied = supplier.get();
                    if (supplied instanceof String text && !text.isBlank()) {
                        return text;
                    }
                }
            } catch (ReflectiveOperationException | IllegalStateException ignored) {
            }
        }
        return null;
    }

}
