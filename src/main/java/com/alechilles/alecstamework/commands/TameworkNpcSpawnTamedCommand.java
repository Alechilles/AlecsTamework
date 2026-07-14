package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.NpcSpawnCommandService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
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

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

/**
 * Spawns owned, tamed NPCs with typed role/count arguments and live role completion.
 */
public final class TameworkNpcSpawnTamedCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> roleArg = withRequiredArg("role", "NPC role to spawn.", STRING)
            .suggest((sender, entered, parameters, suggestions) -> TameworkNpcRoleResolver.suggestRoles(entered, suggestions));
    private final DefaultArg<Integer> countArg = withDefaultArg(
            "count", "Number of NPCs to spawn.", INTEGER, 1, "1"
    );
    private final OptionalArg<Double> radiusArg = withOptionalArg(
            "radius", "Maximum radius for the batch spawn formation.", DOUBLE
    );
    private final OptionalArg<String> attachmentArg = withOptionalArg(
            "attachment", "Attachment override formatted as slot:value.", STRING
    );

    public TameworkNpcSpawnTamedCommand() {
        super("spawntamed", "Spawn owned and tamed NPCs from a role id.");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        int count = countArg.get(commandContext);
        if (count <= 0) {
            commandContext.sender().sendMessage(Message.raw("--count must be greater than zero."));
            return;
        }
        Double radius = radiusArg.provided(commandContext) ? radiusArg.get(commandContext) : null;
        if (radius != null && (!Double.isFinite(radius) || radius <= 0.0)) {
            commandContext.sender().sendMessage(Message.raw("--radius must be a positive number."));
            return;
        }
        Map<String, String> attachments = parseAttachment(
                attachmentArg.provided(commandContext) ? attachmentArg.get(commandContext) : null,
                commandContext
        );
        if (attachments == null) {
            return;
        }

        String requestedRole = roleArg.get(commandContext);
        TameworkNpcRoleResolver.RoleResolution resolution =
                TameworkNpcRoleResolver.resolveRole(requestedRole, NPCPlugin.get());
        if (resolution.errorMessage() != null || resolution.roleId() == null) {
            commandContext.sender().sendMessage(Message.raw(
                    resolution.errorMessage() != null ? resolution.errorMessage() : "Unable to resolve role id."
            ));
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        Tamework plugin = Tamework.getInstance();
        if (player == null || plugin == null) {
            commandContext.sender().sendMessage(Message.raw(player == null ? "No player context." : "Tamework plugin not available."));
            return;
        }
        String roleId = resolution.roleId();
        new NpcSpawnCommandService(plugin).spawnTamedOwnedBatch(
                player, store, ref, world, roleId, count, radius, attachments,
                result -> sendSpawnResult(commandContext, roleId, result)
        );
    }

    @Nullable
    private static Map<String, String> parseAttachment(@Nullable String raw, @Nonnull CommandContext context) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator == raw.length() - 1
                || raw.substring(0, separator).trim().isBlank()
                || raw.substring(separator + 1).trim().isBlank()) {
            context.sender().sendMessage(Message.raw(
                    "--attachment must use slot:value, for example --attachment=coat:browntabby."
            ));
            return null;
        }
        Map<String, String> attachments = new LinkedHashMap<>();
        attachments.put(raw.substring(0, separator).trim(), raw.substring(separator + 1).trim());
        return attachments;
    }

    private static void sendSpawnResult(@Nonnull CommandContext context,
                                        @Nonnull String roleId,
                                        @Nonnull NpcSpawnCommandService.SpawnBatchResult result) {
        if (result.getFailureMessage() != null) {
            context.sendMessage(Message.raw(result.getFailureMessage()));
            return;
        }
        StringBuilder message = new StringBuilder("Spawned ");
        message.append(result.getSpawnedCount()).append('/').append(result.getRequestedCount())
                .append(" tamed NPC(s) with role '").append(roleId).append("'.");
        if (result.hadHeldCommandItem()) {
            message.append(" Auto-linked ").append(result.getLinkedCount()).append(" to the held command item.");
        } else {
            message.append(" No command item was held for auto-linking.");
        }
        if (result.getAppliedAttachments() != null && !result.getAppliedAttachments().isEmpty()) {
            message.append(" Applied attachments: ").append(formatAttachments(result.getAppliedAttachments())).append('.');
        }
        if (!result.getInvalidAttachments().isEmpty()) {
            message.append(" Ignored invalid attachments: ")
                    .append(String.join(", ", result.getInvalidAttachments())).append('.');
        }
        if (result.getStoppedReason() != null) {
            message.append(' ').append(result.getStoppedReason());
        }
        context.sendMessage(Message.raw(message.toString()));
    }

    private static String formatAttachments(@Nonnull Map<String, String> attachments) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : attachments.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return builder.toString();
    }
}
