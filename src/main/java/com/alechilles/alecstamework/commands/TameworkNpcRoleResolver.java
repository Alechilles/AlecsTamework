package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves NPC role ids from command input and matches live NPCs against resolved roles.
 */
final class TameworkNpcRoleResolver {
    private TameworkNpcRoleResolver() {
    }

    static boolean matchesRole(@Nonnull String roleId, @Nonnull NPCEntity npc, @Nullable NPCPlugin npcPlugin) {
        String roleName = npc.getRoleName();
        if (equalsIgnoreCase(roleId, roleName)) {
            return true;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex < 0 || npcPlugin == null) {
            return false;
        }
        String resolved = npcPlugin.getName(roleIndex);
        return equalsIgnoreCase(roleId, resolved);
    }

    @Nonnull
    static RoleResolution resolveRole(@Nonnull String requestedRole, @Nullable NPCPlugin npcPlugin) {
        if (npcPlugin == null) {
            return new RoleResolution(requestedRole, null);
        }

        int directIndex = npcPlugin.getIndex(requestedRole);
        if (directIndex >= 0) {
            String directRole = npcPlugin.getName(directIndex);
            if (directRole != null && !directRole.isBlank()) {
                return new RoleResolution(directRole, null);
            }
        }

        List<String> allRoles = npcPlugin.getRoleTemplateNames(true);
        if (allRoles == null || allRoles.isEmpty()) {
            return RoleResolution.failure(
                    "No NPC roles are currently registered.",
                    "tamework.commands.npc.role.noneRegistered", List.of());
        }

        List<String> exactMatches = new ArrayList<>();
        List<String> shortNameMatches = new ArrayList<>();
        String normalizedRequested = normalize(requestedRole);

        for (String role : allRoles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            if (normalize(role).equals(normalizedRequested)) {
                exactMatches.add(role);
                continue;
            }
            if (normalize(shortRoleName(role)).equals(normalizedRequested)) {
                shortNameMatches.add(role);
            }
        }

        if (exactMatches.size() == 1) {
            return new RoleResolution(exactMatches.get(0), null);
        }
        if (exactMatches.size() > 1) {
            return ambiguousRoleResolution(requestedRole, exactMatches);
        }
        if (shortNameMatches.size() == 1) {
            return new RoleResolution(shortNameMatches.get(0), null);
        }
        if (shortNameMatches.size() > 1) {
            return ambiguousRoleResolution(requestedRole, shortNameMatches);
        }

        List<String> suggestions = suggestRoles(normalizedRequested, allRoles);
        if (suggestions.isEmpty()) {
            return RoleResolution.failure(
                    "No role matched '" + requestedRole + "'.",
                    "tamework.commands.npc.role.notFound", List.of(requestedRole));
        }
        return RoleResolution.failure(
                "No role matched '" + requestedRole + "'. Try one of: "
                        + String.join(", ", suggestions) + ".",
                "tamework.commands.npc.role.suggestions",
                List.of(requestedRole, String.join(", ", suggestions)));
    }

    @Nonnull
    private static RoleResolution ambiguousRoleResolution(@Nonnull String requestedRole,
                                                           @Nonnull List<String> matches) {
        List<String> limited = matches.size() > 8 ? matches.subList(0, 8) : matches;
        String choices = String.join(", ", limited);
        return RoleResolution.failure(
                "Role '" + requestedRole + "' is ambiguous. Use one of: " + choices + ".",
                "tamework.commands.npc.role.ambiguous", List.of(requestedRole, choices));
    }

    @Nonnull
    private static List<String> suggestRoles(@Nonnull String normalizedRequested, @Nonnull List<String> allRoles) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (String role : allRoles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String normalizedRole = normalize(role);
            String normalizedShort = normalize(shortRoleName(role));
            if (normalizedRole.contains(normalizedRequested) || normalizedShort.contains(normalizedRequested)) {
                suggestions.add(role);
                if (suggestions.size() >= 8) {
                    break;
                }
            }
        }
        return new ArrayList<>(suggestions);
    }

    @Nonnull
    private static String shortRoleName(@Nonnull String roleId) {
        int slash = Math.max(roleId.lastIndexOf('/'), roleId.lastIndexOf('\\'));
        String shortName = slash >= 0 && slash + 1 < roleId.length() ? roleId.substring(slash + 1) : roleId;
        int dot = shortName.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < shortName.length()) {
            shortName = shortName.substring(dot + 1);
        }
        return shortName;
    }

    @Nonnull
    private static String normalize(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(@Nonnull String left, @Nullable String right) {
        return right != null && left.equalsIgnoreCase(right);
    }

    static final class RoleResolution {
        @Nullable
        private final String roleId;
        @Nullable
        private final String errorMessage;
        @Nullable
        private final String errorKey;
        @Nonnull
        private final List<String> errorArguments;

        private RoleResolution(@Nullable String roleId, @Nullable String errorMessage) {
            this(roleId, errorMessage, null, List.of());
        }

        private RoleResolution(@Nullable String roleId,
                               @Nullable String errorMessage,
                               @Nullable String errorKey,
                               @Nonnull List<String> errorArguments) {
            this.roleId = roleId;
            this.errorMessage = errorMessage;
            this.errorKey = errorKey;
            this.errorArguments = List.copyOf(errorArguments);
        }

        @Nonnull
        private static RoleResolution failure(@Nonnull String message,
                                              @Nonnull String key,
                                              @Nonnull List<String> arguments) {
            return new RoleResolution(null, message, key, arguments);
        }

        @Nullable
        String roleId() {
            return roleId;
        }

        @Nullable
        String errorMessage() {
            return errorMessage;
        }

        @Nullable
        String errorKey() {
            return errorKey;
        }

        @Nonnull
        List<String> errorArguments() {
            return errorArguments;
        }
    }
}
