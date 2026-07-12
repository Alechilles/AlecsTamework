package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.NpcSpawnCommandService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spawns one or more owned+tamed NPCs from a requested role id and auto-links a held command item.
 */
public final class TameworkNpcSpawnTamedCommand extends AbstractPlayerCommand {
    public TameworkNpcSpawnTamedCommand() {
        super("npcspawntamed", "Spawn owned+tamed NPCs from a role id.");
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
            commandContext.sender().sendMessage(Message.raw("Usage: /tw npcspawntamed <roleId> <quantity>"));
            return;
        }

        Integer quantity = parsePositiveInt(getArg(commandContext, 3));
        if (quantity == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw npcspawntamed <roleId> <quantity>"));
            return;
        }
        ParsedAttachments parsedAttachments = parseAttachments(commandContext);
        if (parsedAttachments.errorMessage != null) {
            commandContext.sender().sendMessage(Message.raw(parsedAttachments.errorMessage));
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        TameworkNpcRoleResolver.RoleResolution resolution = TameworkNpcRoleResolver.resolveRole(requestedRole.trim(), npcPlugin);
        if (resolution.errorMessage() != null) {
            commandContext.sender().sendMessage(Message.raw(resolution.errorMessage()));
            return;
        }

        String roleId = resolution.roleId();
        if (roleId == null || roleId.isBlank()) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve role id: " + requestedRole));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandContext.sender().sendMessage(Message.raw("No player context."));
            return;
        }

        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            commandContext.sender().sendMessage(Message.raw("Tamework plugin not available."));
            return;
        }

        new NpcSpawnCommandService(plugin).spawnTamedOwnedBatch(
                player,
                store,
                ref,
                world,
                roleId,
                quantity,
                parsedAttachments.attachments,
                result -> sendSpawnResult(commandContext, roleId, result)
        );
    }

    private static void sendSpawnResult(@Nonnull CommandContext commandContext,
                                        @Nonnull String roleId,
                                        @Nonnull NpcSpawnCommandService.SpawnBatchResult result) {
        if (result.getFailureMessage() != null) {
            commandContext.sender().sendMessage(Message.raw(result.getFailureMessage()));
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Spawned ")
                .append(result.getSpawnedCount())
                .append("/")
                .append(result.getRequestedCount())
                .append(" tamed NPC(s) with role '")
                .append(roleId)
                .append("'.");
        if (result.hadHeldCommandItem()) {
            message.append(" Auto-linked ").append(result.getLinkedCount()).append(" to the held command item.");
        } else {
            message.append(" No command item was held for auto-linking.");
        }
        if (result.getAppliedAttachments() != null && !result.getAppliedAttachments().isEmpty()) {
            message.append(" Applied attachments: ").append(formatAttachments(result.getAppliedAttachments())).append(".");
        }
        if (!result.getInvalidAttachments().isEmpty()) {
            message.append(" Ignored invalid attachments: ")
                    .append(String.join(", ", result.getInvalidAttachments()))
                    .append(".");
        }
        if (result.getStoppedReason() != null) {
            message.append(' ').append(result.getStoppedReason());
        }
        commandContext.sender().sendMessage(Message.raw(message.toString()));
    }

    private static ParsedAttachments parseAttachments(@Nonnull CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return ParsedAttachments.empty();
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= 4) {
            return ParsedAttachments.empty();
        }
        LinkedHashMap<String, String> attachments = new LinkedHashMap<>();
        for (int index = 4; index < tokens.length; index++) {
            String token = tokens[index];
            if (token == null || token.isBlank()) {
                continue;
            }
            int separator = token.indexOf(':');
            if (separator <= 0 || separator == token.length() - 1) {
                return ParsedAttachments.error(
                        "Invalid attachment '" + token + "'. Use <slot:value>, for example coat:browntabby."
                );
            }
            String slot = token.substring(0, separator).trim();
            String value = token.substring(separator + 1).trim();
            if (slot.isBlank() || value.isBlank()) {
                return ParsedAttachments.error(
                        "Invalid attachment '" + token + "'. Use <slot:value>, for example coat:browntabby."
                );
            }
            attachments.put(slot, value);
        }
        return attachments.isEmpty() ? ParsedAttachments.empty() : new ParsedAttachments(attachments, null);
    }

    private static String formatAttachments(Map<String, String> attachments) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : attachments.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append(':').append(entry.getValue());
            first = false;
        }
        return builder.toString();
    }

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

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class ParsedAttachments {
        @Nullable
        private final Map<String, String> attachments;
        @Nullable
        private final String errorMessage;

        private ParsedAttachments(@Nullable Map<String, String> attachments, @Nullable String errorMessage) {
            this.attachments = attachments;
            this.errorMessage = errorMessage;
        }

        private static ParsedAttachments empty() {
            return new ParsedAttachments(null, null);
        }

        private static ParsedAttachments error(String errorMessage) {
            return new ParsedAttachments(null, errorMessage);
        }
    }
}
