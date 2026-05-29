package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to set companion level for the targeted NPC.
 */
public final class TameworkSetLevelCommand extends AbstractPlayerCommand {
    public TameworkSetLevelCommand() {
        super("setlevel", "Set level of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Integer requestedLevel = parseRequestedLevel(commandContext);
        if (requestedLevel == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw setlevel <level>"));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        CompanionLevelingService.SetLevelResult result = CompanionLevelingService.setLevel(
                candidate.ref,
                store,
                requestedLevel
        );
        if (!result.applied()) {
            commandContext.sender().sendMessage(Message.raw(
                    "Could not set level for NPC " + candidate.npcUuid + ": " + result.reason() + "."
            ));
            return;
        }

        commandContext.sender().sendMessage(Message.raw(buildResultMessage(
                candidate.npcUuid.toString(),
                requestedLevel,
                result
        )));
    }

    @Nullable
    static Integer parseRequestedLevel(@Nonnull CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        try {
            return Integer.parseInt(tokens[2]);
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
    private static String formatXp(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0, value));
    }
}
