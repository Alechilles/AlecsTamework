package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;

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
            return new RoleResolution(null, "No NPC roles are currently registered.");
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
            return new RoleResolution(null, ambiguousRoleMessage(requestedRole, exactMatches));
        }
        if (shortNameMatches.size() == 1) {
            return new RoleResolution(shortNameMatches.get(0), null);
        }
        if (shortNameMatches.size() > 1) {
            return new RoleResolution(null, ambiguousRoleMessage(requestedRole, shortNameMatches));
        }

        List<String> suggestions = suggestRoles(normalizedRequested, allRoles);
        if (suggestions.isEmpty()) {
            return new RoleResolution(null, "No role matched '" + requestedRole + "'.");
        }
        return new RoleResolution(
                null,
                "No role matched '" + requestedRole + "'. Try one of: " + String.join(", ", suggestions) + "."
        );
    }

    /** Adds live NPC role ids matching the text currently entered in command completion. */
    static void suggestRoles(@Nonnull String entered, @Nonnull SuggestionResult suggestions) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        List<String> roles = npcPlugin != null ? npcPlugin.getRoleTemplateNames(true) : null;
        if (roles == null || roles.isEmpty()) {
            return;
        }
        String normalized = normalize(entered);
        int count = 0;
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            if (normalized.isEmpty() || normalize(role).startsWith(normalized)
                    || normalize(shortRoleName(role)).startsWith(normalized)) {
                suggestions.suggest(role);
                if (++count >= 20) {
                    return;
                }
            }
        }
    }

    @Nonnull
    private static String ambiguousRoleMessage(@Nonnull String requestedRole, @Nonnull List<String> matches) {
        List<String> limited = matches.size() > 8 ? matches.subList(0, 8) : matches;
        return "Role '" + requestedRole + "' is ambiguous. Use one of: " + String.join(", ", limited) + ".";
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

        private RoleResolution(@Nullable String roleId, @Nullable String errorMessage) {
            this.roleId = roleId;
            this.errorMessage = errorMessage;
        }

        @Nullable
        String roleId() {
            return roleId;
        }

        @Nullable
        String errorMessage() {
            return errorMessage;
        }
    }
}
