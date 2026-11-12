package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to set companion level for the targeted NPC.
 */
public final class TameworkSetLevelCommand extends AbstractTameworkNpcTargetCommand {
    public TameworkSetLevelCommand() {
        super("level", "server.tamework.commands.setLevel.description",
                "server.tamework.commands.setLevel.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        Integer requestedLevel = parseRequestedLevel(commandContext);
        if (requestedLevel == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setLevel.usage.tw.setlevel.level"));
            return;
        }

        CompanionLevelingService.SetLevelResult result = CompanionLevelingService.setLevel(
                npcRef,
                store,
                requestedLevel
        );
        if (!result.applied()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setLevel.unavailable")
                    .param("0", String.valueOf(npc.getUuid())));
            return;
        }

        if (!commandContext.isPlayer()) {
            commandContext.sendMessage(Message.raw("ok entity=" + npc.getUuid()
                    + " requested=" + requestedLevel
                    + " level=" + result.previousLevel() + "->" + result.currentLevel()
                    + " maxLevel=" + result.maxLevel()
                    + " totalXp=" + formatXp(result.totalXp())));
            return;
        }
        commandContext.sender().sendMessage(buildLocalizedResultMessage(
                npc.getUuid().toString(), requestedLevel, result));
    }

    @Nullable
    static Integer parseRequestedLevel(@Nonnull CommandContext commandContext) {
        try {
            String argument = TameworkCommandInput.firstArgument(
                    commandContext.getInputString(), "level"
            );
            return argument == null ? null : Integer.parseInt(argument);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    static String buildResultMessage(@Nonnull String npcUuid,
                                     int requestedLevel,
                                     @Nonnull CompanionLevelingService.SetLevelResult result) {
        StringBuilder message = new StringBuilder();
        message.append("Set level for NPC ")
                .append(npcUuid)
                .append(": requested=")
                .append(requestedLevel)
                .append(", previous=")
                .append(result.previousLevel())
                .append(", applied=")
                .append(result.currentLevel())
                .append("/")
                .append(result.maxLevel())
                .append(", totalXp=")
                .append(formatXp(result.totalXp()));
        if (requestedLevel != result.currentLevel()) {
            message.append(" (clamped)");
        }
        message.append(".");
        return message.toString();
    }

    @Nonnull
    private static Message buildLocalizedResultMessage(@Nonnull String npcUuid,
                                                       int requestedLevel,
                                                       @Nonnull CompanionLevelingService.SetLevelResult result) {
        String key = requestedLevel == result.currentLevel()
                ? "server.tamework.commands.setLevel.result"
                : "server.tamework.commands.setLevel.result.clamped";
        return Message.translation(key)
                .param("0", npcUuid)
                .param("1", String.valueOf(requestedLevel))
                .param("2", String.valueOf(result.previousLevel()))
                .param("3", String.valueOf(result.currentLevel()))
                .param("4", String.valueOf(result.maxLevel()))
                .param("5", formatXp(result.totalXp()));
    }

    @Nonnull
    private static String formatXp(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0, value));
    }
}
