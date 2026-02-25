package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to display hunger/thirst needs state for the targeted NPC.
 */
public final class TameworkGetNeedsCommand extends AbstractPlayerCommand {
    public TameworkGetNeedsCommand() {
        super("getneeds", "Get needs (hunger/thirst) for the NPC you are looking at.");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            commandContext.sender().sendMessage(Message.raw("Needs component type is not registered."));
            return;
        }
        TameworkNeedsComponent needs = store.getComponent(candidate.ref, needsType);
        if (needs == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC " + candidate.npcUuid + " has no tracked needs state."
            ));
            return;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(candidate.ref, store, needs);
        commandContext.sender().sendMessage(Message.raw(buildMessage(candidate.npcUuid, needs, config)));
    }

    private static String buildMessage(@Nonnull UUID npcUuid,
                                       @Nonnull TameworkNeedsComponent needs,
                                       @Nullable TwNeedsConfig config) {
        StringBuilder message = new StringBuilder();
        message.append("Needs for NPC ")
                .append(npcUuid)
                .append(": hunger=")
                .append(format(needs.getHunger()))
                .append(", thirst=")
                .append(format(needs.getThirst()))
                .append(", appliedPenalty=")
                .append(format(needs.getAppliedHappinessPenalty()));
        if (config != null) {
            TwNeedsConfig.ValueSettings values = config.getValues();
            message.append(", hunger%=").append(formatPercent(needs.getHunger(), values.getHungerMin(), values.getHungerMax()));
            message.append(", thirst%=").append(formatPercent(needs.getThirst(), values.getThirstMin(), values.getThirstMax()));
            message.append(", config=").append(config.getId());
            message.append(", timing=").append(config.getTiming().getTimerBasis().toConfigValue());
        }
        if (needs.getLastUpdateMs() > 0L) {
            message.append(", lastUpdateMs=").append(needs.getLastUpdateMs());
        }
        if (needs.getLastPassiveSweepMs() > 0L) {
            message.append(", lastPassiveSweepMs=").append(needs.getLastPassiveSweepMs());
        }
        message.append(".");
        return message.toString();
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int formatPercent(double current, double min, double max) {
        if (!Double.isFinite(current) || !Double.isFinite(min) || !Double.isFinite(max)) {
            return 0;
        }
        double range = max - min;
        if (range <= 0.0) {
            return 0;
        }
        double clamped = Math.max(min, Math.min(max, current));
        return (int) Math.round(((clamped - min) / range) * 100.0);
    }
}
