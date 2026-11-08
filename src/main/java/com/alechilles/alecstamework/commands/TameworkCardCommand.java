package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.alechilles.alecstamework.companioncard.CompanionCardPreferences;
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

/**
 * {@code /tw card [card|cycle|default]}: shows or sets what F on a companion does for the calling player.
 * {@code default} clears the player's own choice so they follow the server default.
 */
public final class TameworkCardCommand extends AbstractPlayerCommand {
    private static final String KEY = "server.tamework.commands.card.";
    private static final String COMMAND_TOKEN = "card";

    public TameworkCardCommand() {
        super(COMMAND_TOKEN, KEY + "description");
        // Every player may run it: it changes only the caller's own preference, and the server lock is
        // checked in execute. Without this the /tw root's admin permission would hide it from players.
        requireNoPermission();
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        String argument = TameworkCommandInput.firstArgument(commandContext.getInputString(), COMMAND_TOKEN);
        if (argument == null || argument.isBlank()) {
            reportCurrent(commandContext, ref, store);
            return;
        }
        String choice = argument.trim().toLowerCase(Locale.ROOT);
        CompanionCardFBehaviour requested;
        if ("default".equals(choice)) {
            requested = null;
        } else {
            requested = CompanionCardFBehaviour.fromConfigValueOrNull(choice);
            if (requested == null) {
                commandContext.sender().sendMessage(Message.translation(KEY + "unknown").param("0", argument.trim()));
                return;
            }
        }
        if (!CompanionCardPreferences.playerChoiceAllowed()) {
            commandContext.sender().sendMessage(Message.translation(KEY + "locked")
                    .param("0", behaviourName(CompanionCardPreferences.serverDefault())));
            return;
        }
        CompanionCardPreferences.setOverride(ref, store, requested);
        CompanionCardFBehaviour effective = CompanionCardPreferences.effective(ref, store);
        commandContext.sender().sendMessage(Message.translation(KEY + (requested == null ? "cleared" : "set"))
                .param("0", behaviourName(effective)));
    }

    private static void reportCurrent(@Nonnull CommandContext commandContext,
                                      @Nonnull Ref<EntityStore> ref,
                                      @Nonnull Store<EntityStore> store) {
        String key;
        if (!CompanionCardPreferences.playerChoiceAllowed()) {
            key = "current.locked";
        } else if (CompanionCardPreferences.savedOverride(ref, store) != null) {
            key = "current.own";
        } else {
            key = "current.default";
        }
        commandContext.sender().sendMessage(Message.translation(KEY + key)
                .param("0", behaviourName(CompanionCardPreferences.effective(ref, store))));
    }

    @Nonnull
    private static Message behaviourName(@Nonnull CompanionCardFBehaviour behaviour) {
        return Message.translation(KEY + "behaviour." + switch (behaviour) {
            case CARD -> "card";
            case CYCLE -> "cycle";
        });
    }
}
