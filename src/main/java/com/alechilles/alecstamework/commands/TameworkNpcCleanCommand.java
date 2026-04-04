package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to remove all live NPC entities matching one requested role id.
 */
public final class TameworkNpcCleanCommand extends AbstractPlayerCommand {
    public TameworkNpcCleanCommand() {
        super("npcclean", "Remove all NPCs matching a specific role id.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        String requestedRole = getArg(commandContext, 2);
        if (requestedRole == null || requestedRole.isBlank()) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw npcclean <roleId>"));
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        RoleResolution resolution = resolveRole(requestedRole.trim(), npcPlugin);
        if (resolution.errorMessage != null) {
            commandContext.sender().sendMessage(Message.raw(resolution.errorMessage));
            return;
        }

        String targetRoleId = resolution.roleId;
        if (targetRoleId == null || targetRoleId.isBlank()) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve role id: " + requestedRole));
            return;
        }

        AtomicInteger removedCount = new AtomicInteger(0);
        store.forEachEntityParallel(NPCEntity.getComponentType(), (index, archetypeChunk, commandBuffer) -> {
            NPCEntity npc = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            if (!matchesRole(targetRoleId, npc, npcPlugin)) {
                return;
            }
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            removedCount.incrementAndGet();
        });

        int removed = removedCount.get();
        if (removed == 0) {
            commandContext.sender().sendMessage(Message.raw("No NPCs matched role '" + targetRoleId + "'."));
            return;
        }
        commandContext.sender().sendMessage(Message.raw("Removed " + removed + " NPC(s) with role '" + targetRoleId + "'."));
    }

    private static boolean matchesRole(@Nonnull String roleId, @Nonnull NPCEntity npc, @Nullable NPCPlugin npcPlugin) {
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
    private static RoleResolution resolveRole(@Nonnull String requestedRole, @Nullable NPCPlugin npcPlugin) {
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

    @Nullable
    private static String getArg(@Nonnull CommandContext commandContext, int index) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= index) {
            return null;
        }
        return tokens[index];
    }

    private static final class RoleResolution {
        @Nullable
        private final String roleId;
        @Nullable
        private final String errorMessage;

        private RoleResolution(@Nullable String roleId, @Nullable String errorMessage) {
            this.roleId = roleId;
            this.errorMessage = errorMessage;
        }
    }
}
