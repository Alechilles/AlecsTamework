package com.alechilles.alecstamework.commands;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;

import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
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

/**
 * Fast-forwards growth and aging of the selected NPCs, so a test does not wait in real time. The owner
 * progression clock's offline rule decides how much of the time counts; the reply names both, one
 * machine-readable line per NPC.
 */
public final class TameworkAdvanceGrowthCommand extends NPCMultiSelectCommandBase {
    /** One year; keeps the millisecond sum far from overflow. */
    private static final double MAX_SECONDS = 31_536_000.0;

    private final RequiredArg<Double> secondsArg =
            withRequiredArg("seconds", "server.tamework.commands.advanceGrowth.argument.seconds", DOUBLE);

    public TameworkAdvanceGrowthCommand() {
        super("advance", "server.tamework.commands.advanceGrowth.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull NPCEntity npc, @Nonnull World world,
                           @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        double seconds = secondsArg.get(context);
        if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds > MAX_SECONDS) {
            context.sendMessage(Message.raw("refused reason=invalid-seconds max=" + format(MAX_SECONDS)));
            return;
        }
        CompanionLifeStageService.GrowthAdvanceResult result = CompanionLifeStageService.advanceGrowthForDebug(
                npcRef, npc, store, Math.round(seconds * 1000.0));
        if (result == null) {
            context.sendMessage(Message.raw("refused reason=no-life-stage entity=" + npc.getUuid()));
            return;
        }
        context.sendMessage(Message.raw("ok entity=" + npc.getUuid()
                + " requestedSeconds=" + format(result.requestedMs() / 1000.0)
                + " countedSeconds=" + format(result.countedMs() / 1000.0)
                + " stage=" + result.stageBefore() + "->" + result.stageAfter()
                + " role=" + npc.getRoleName()
                + " roleChange=" + (result.roleChangeRequested() ? "requested" : "none")
                + " oldAgeDeath=" + result.oldAgeDeath()));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", Double.isFinite(value) ? value : 0.0);
    }
}
