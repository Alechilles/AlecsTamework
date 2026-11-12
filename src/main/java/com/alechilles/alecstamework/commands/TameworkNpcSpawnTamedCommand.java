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
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeDoublePosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.RELATIVE_POSITION;
import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;
import static com.hypixel.hytale.server.npc.commands.NPCCommand.NPC_ROLE;

/**
 * Spawns owned, tamed NPCs with typed role/count arguments and live role completion. A player
 * without {@code --owner} owns them, and they appear in front of the player, linked to a held command
 * item. With {@code --owner <uuid|name> --at <x> <y> <z>} (and {@code --world} for another world) they
 * are spawned for that owner, who need not be online, at that position; this form works from the
 * console and replies with one machine-readable line.
 */
public final class TameworkNpcSpawnTamedCommand extends AbstractWorldCommand {
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
    private final OptionalArg<String> ownerArg = withOptionalArg(
            "owner", "server.tamework.commands.npcSpawnTamed.argument.owner", STRING
    );
    private final OptionalArg<RelativeDoublePosition> atArg = withOptionalArg(
            "at", "server.tamework.commands.npcSpawnTamed.argument.at", RELATIVE_POSITION
    );

    public TameworkNpcSpawnTamedCommand() {
        super("tamed", "server.tamework.commands.npcSpawnTamed.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        if (ownerArg.provided(commandContext)) {
            executeForOwner(commandContext, world, store);
            return;
        }
        Ref<EntityStore> ref = commandContext.isPlayer() ? commandContext.senderAsPlayerRef() : null;
        if (ref == null) {
            commandContext.sendMessage(Message.raw("refused reason=owner-required option=--owner"));
            return;
        }
        // The player spawns in front of themself; --world elsewhere has no player to spawn in front of.
        if (!ref.isValid() || ref.getStore() != store) {
            commandContext.sendMessage(Message.translation("server.tamework.commands.npcSpawnTamed.playerContextUnavailable"));
            return;
        }
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

    /**
     * The {@code --owner} form: the same spawn flow and owner write as a player's spawn, for an owner
     * who need not be online, at {@code --at}. Runs on the target world's thread and replies there.
     */
    private void executeForOwner(@Nonnull CommandContext context, @Nonnull World world,
                                 @Nonnull Store<EntityStore> store) {
        String rawOwner = ownerArg.get(context);
        TameworkCommandPlayers.Named owner = TameworkCommandPlayers.resolve(rawOwner);
        if (owner == null) {
            context.sendMessage(Message.raw("refused reason=unknown-owner value="
                    + (rawOwner == null || rawOwner.isBlank() ? "-" : rawOwner.trim())));
            return;
        }
        if (!atArg.provided(context)) {
            context.sendMessage(Message.raw("refused reason=position-required option=--at"));
            return;
        }
        int count = countArg.get(context);
        if (count <= 0) {
            context.sendMessage(Message.raw("refused reason=invalid-count"));
            return;
        }
        Double radius = radiusArg.provided(context) ? radiusArg.get(context) : null;
        if (radius != null && (!Double.isFinite(radius) || radius <= 0.0)) {
            context.sendMessage(Message.raw("refused reason=invalid-radius"));
            return;
        }
        Map<String, String> attachments = parseAttachment(
                attachmentArg.provided(context) ? attachmentArg.get(context) : null, context);
        if (attachments == null) {
            return;
        }
        String roleId = NPCPlugin.get().getName(roleArg.get(context).getIndex());
        Tamework plugin = Tamework.getInstance();
        if (roleId == null || roleId.isBlank() || plugin == null) {
            context.sendMessage(Message.raw(plugin == null ? "refused reason=plugin-unavailable"
                    : "refused reason=unknown-role"));
            return;
        }
        RelativeDoublePosition at = atArg.get(context);
        Ref<EntityStore> sender = context.isPlayer() ? context.senderAsPlayerRef() : null;
        if (at.isRelative() && (sender == null || !sender.isValid() || sender.getStore() != store)) {
            // A relative position needs the sender in this world (the engine refuses it for the console).
            context.sendMessage(Message.raw("refused reason=absolute-position-required option=--at"));
            return;
        }
        Vector3d position = at.getRelativePosition(context, world, store);
        Universe universe = Universe.get();
        PlayerRef online = universe == null ? null : universe.getPlayer(owner.uuid());
        Ref<EntityStore> ownerRef = online == null ? null : online.getReference();
        new NpcSpawnCommandService(plugin).spawnTamedOwnedBatchAt(
                owner.uuid(), owner.name(), ownerRef, store, world, position, roleId, count, radius, attachments,
                result -> context.sendMessage(Message.raw(ownerSpawnReply(roleId, owner.uuid(), world, position, result)))
        );
    }

    @Nonnull
    private static String ownerSpawnReply(@Nonnull String roleId, @Nonnull UUID owner, @Nonnull World world,
                                          @Nonnull Vector3d position,
                                          @Nonnull NpcSpawnCommandService.SpawnBatchResult result) {
        if (result.getFailureMessage() != null) {
            return "refused reason=" + reasonToken(result.getFailureMessageKey(), "spawn-failed")
                    + " role=" + roleId + " owner=" + owner;
        }
        if (result.getSpawnedNpcIds().isEmpty()) {
            return "refused reason=" + reasonToken(result.getStoppedReasonKey(), "spawn-failed")
                    + " role=" + roleId + " owner=" + owner + " spawned=0 requested=" + result.getRequestedCount();
        }
        StringBuilder reply = new StringBuilder("ok role=").append(roleId)
                .append(" owner=").append(owner)
                .append(" entity=").append(String.join(",",
                        result.getSpawnedNpcIds().stream().map(UUID::toString).toList()))
                .append(" world=").append(world.getName())
                .append(" x=").append(format(position.x))
                .append(" y=").append(format(position.y))
                .append(" z=").append(format(position.z));
        if (result.getStoppedReason() != null) {
            reply.append(" stopped=").append(reasonToken(result.getStoppedReasonKey(), "spawn-failed"))
                    .append(" spawned=").append(result.getSpawnedCount())
                    .append(" requested=").append(result.getRequestedCount());
        }
        if (!result.getInvalidAttachments().isEmpty()) {
            reply.append(" invalidAttachments=").append(String.join(",", result.getInvalidAttachments()));
        }
        return reply.toString();
    }

    /** The last part of a result's presentation key in kebab case, e.g. {@code population-cap}. */
    @Nonnull
    static String reasonToken(@Nullable String key, @Nonnull String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String last = key.substring(key.lastIndexOf('.') + 1);
        return last.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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
