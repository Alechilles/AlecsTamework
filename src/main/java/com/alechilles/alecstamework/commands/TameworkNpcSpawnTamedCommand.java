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
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;
import static com.hypixel.hytale.server.npc.commands.NPCCommand.NPC_ROLE;

/**
 * Spawns owned, tamed NPCs with typed role/count arguments and live role completion.
 */
public final class TameworkNpcSpawnTamedCommand extends AbstractPlayerCommand {
    /** Uses Hytale's native role type so tab completion mirrors {@code /npc spawn}. */
    private final RequiredArg<BuilderInfo> roleArg = withRequiredArg("role", "server.tamework.commands.npcSpawnTamed.argument.role", NPC_ROLE);
    private final DefaultArg<Integer> countArg = withDefaultArg(
            "count", "server.tamework.commands.npcSpawnTamed.argument.count", INTEGER, 1, "1"
    );
    private final OptionalArg<Double> radiusArg = withOptionalArg(
            "radius", "server.tamework.commands.npcSpawnTamed.argument.radius", DOUBLE
    );
    private final OptionalArg<String> attachmentArg = withOptionalArg(
            "attachment", "server.tamework.commands.npcSpawnTamed.argument.attachment", STRING
    );

    public TameworkNpcSpawnTamedCommand() {
        super("tamed", "server.tamework.commands.npcSpawnTamed.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        int count = countArg.get(commandContext);
        if (count <= 0) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcSpawnTamed.count.must.be.greater.than.zero"));
            return;
        }
        Double radius = radiusArg.provided(commandContext) ? radiusArg.get(commandContext) : null;
        if (radius != null && (!Double.isFinite(radius) || radius <= 0.0)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcSpawnTamed.radius.must.be.a.positive.number"));
            return;
        }
        Map<String, String> attachments = parseAttachment(
                attachmentArg.provided(commandContext) ? attachmentArg.get(commandContext) : null,
                commandContext
        );
        if (attachments == null) {
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        BuilderInfo roleInfo = roleArg.get(commandContext);
        String roleId = npcPlugin.getName(roleInfo.getIndex());
        if (roleId == null || roleId.isBlank()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcSpawnTamed.unable.to.resolve.npc.role.id"));
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        Tamework plugin = Tamework.getInstance();
        if (player == null || plugin == null) {
            commandContext.sender().sendMessage(Message.translation(player == null
                    ? "server.tamework.commands.npcSpawnTamed.playerContextUnavailable"
                    : "server.tamework.commands.npcSpawnTamed.pluginUnavailable"));
            return;
        }
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
            context.sender().sendMessage(Message.translation("server.tamework.commands.npcSpawnTamed.attachment.must.use.slot.value.for.example"));
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
            context.sendMessage(resultMessage(
                    result.getFailureMessageKey(), result.getFailureMessage(), roleId));
            return;
        }
        Message message = Message.translation("server.tamework.commands.npcSpawnTamed.result.summary")
                .param("spawned", result.getSpawnedCount())
                .param("requested", result.getRequestedCount())
                .param("role", roleId);
        if (result.hadHeldCommandItem()) {
            message.insert(" ").insert(Message.translation(
                    "server.tamework.commands.npcSpawnTamed.result.autoLinked")
                    .param("linked", result.getLinkedCount()));
        } else {
            message.insert(" ").insert(Message.translation(
                    "server.tamework.commands.npcSpawnTamed.result.noHeldCommandItem"));
        }
        if (result.getAppliedAttachments() != null && !result.getAppliedAttachments().isEmpty()) {
            message.insert(" ").insert(Message.translation(
                    "server.tamework.commands.npcSpawnTamed.result.appliedAttachments")
                    .param("attachments", formatAttachments(result.getAppliedAttachments())));
        }
        if (!result.getInvalidAttachments().isEmpty()) {
            message.insert(" ").insert(Message.translation(
                    "server.tamework.commands.npcSpawnTamed.result.invalidAttachments")
                    .param("attachments", String.join(", ", result.getInvalidAttachments())));
        }
        if (result.getStoppedReason() != null) {
            message.insert(" ").insert(resultMessage(
                    result.getStoppedReasonKey(), result.getStoppedReason(), roleId));
        }
        context.sendMessage(message);
    }

    @Nonnull
    private static Message resultMessage(@Nullable String key,
                                         @Nonnull String fallback,
                                         @Nonnull String roleId) {
        if (key == null || key.isBlank()) {
            return Message.raw(fallback);
        }
        return Message.translation("server." + key).param("role", roleId);
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
