package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.commands.NPCMultiSelectCommandBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import javax.annotation.Nonnull;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;

/**
 * Fast-forwards hunger and thirst of the selected NPCs, so a test does not wait in real time.
 * The owner-presence gate still decides how much of the time counts; the reply names both.
 */
public final class TameworkAdvanceNeedsCommand extends NPCMultiSelectCommandBase {
    /** One year; keeps the millisecond sum far from overflow. */
    private static final double MAX_SECONDS = 31_536_000.0;

    private final RequiredArg<Double> secondsArg =
            withRequiredArg("seconds", "server.tamework.commands.advanceNeeds.argument.seconds", DOUBLE);

    public TameworkAdvanceNeedsCommand() {
        super("advance", "server.tamework.commands.advanceNeeds.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull NPCEntity npc, @Nonnull World world,
                           @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        double seconds = secondsArg.get(context);
        if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds > MAX_SECONDS) {
            context.sendMessage(Message.translation("server.tamework.commands.advanceNeeds.invalid.seconds")
                    .param("0", format(MAX_SECONDS)));
            return;
        }
        TameworkNeedsCommandSupport.NeedsContext needsContext = TameworkNeedsCommandSupport.resolveContext(npcRef, store);
        CompanionNeedsService.NeedsAdvanceResult result = needsContext == null ? null
                : CompanionNeedsService.advanceNeedsForDebug(
                        npcRef, store, needsContext.roleId(), Math.round(seconds * 1000.0));
        if (result == null) {
            context.sendMessage(Message.translation("server.tamework.commands.setHunger.no.enabled.needs.config.resolved.for.npc")
                    .param("0", String.valueOf(npc.getUuid())));
            return;
        }
        context.sendMessage(Message.translation("server.tamework.commands.advanceNeeds.advanced.needs.for.npc")
                .param("0", String.valueOf(npc.getUuid()))
                .param("1", format(result.requestedMs() / 1000.0))
                .param("2", format(result.countedMs() / 1000.0))
                .param("3", format(result.hungerBefore()))
                .param("4", format(result.hungerAfter()))
                .param("5", format(result.thirstBefore()))
                .param("6", format(result.thirstAfter())));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", Double.isFinite(value) ? value : 0.0);
    }
}
