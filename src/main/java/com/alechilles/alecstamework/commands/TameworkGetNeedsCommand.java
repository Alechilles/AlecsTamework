package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.commands.NPCMultiSelectCommandBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to display hunger/thirst needs state for the targeted NPC.
 */
public final class TameworkGetNeedsCommand extends NPCMultiSelectCommandBase {
    public TameworkGetNeedsCommand() {
        super("needs", "server.tamework.commands.getNeeds.description");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getNeeds.needs.component.type.is.not.registered"));
            return;
        }
        TameworkNeedsComponent needs = store.getComponent(ref, needsType);
        if (needs == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getNeeds.npc.has.no.tracked.needs.state").param("0", String.valueOf(npc.getUuid())));
            return;
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(ref, store, needs);
        if (!NeedsConfigResolver.isRuntimeEnabled(config)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getNeeds.npc.has.no.tracked.needs.state").param("0", String.valueOf(npc.getUuid())));
            return;
        }
        commandContext.sender().sendMessage(buildLocalizedMessage(npc.getUuid(), needs, config));
    }

    @Nonnull
    private static Message buildLocalizedMessage(@Nonnull UUID npcUuid,
                                                 @Nonnull TameworkNeedsComponent needs,
                                                 @Nullable TwNeedsConfig config) {
        if (config == null) {
            return Message.translation("server.tamework.commands.getNeeds.result")
                    .param("0", String.valueOf(npcUuid))
                    .param("1", format(needs.getHunger()))
                    .param("2", format(needs.getThirst()))
                    .param("3", format(needs.getAppliedHappinessPenalty()))
                    .param("4", String.valueOf(needs.getLastUpdateMs()))
                    .param("5", String.valueOf(needs.getLastPassiveSweepMs()));
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        return Message.translation("server.tamework.commands.getNeeds.result.withConfig")
                .param("0", String.valueOf(npcUuid))
                .param("1", format(needs.getHunger()))
                .param("2", format(needs.getThirst()))
                .param("3", format(needs.getAppliedHappinessPenalty()))
                .param("4", String.valueOf(formatPercent(needs.getHunger(), values.getHungerMin(), values.getHungerMax())))
                .param("5", String.valueOf(formatPercent(needs.getThirst(), values.getThirstMin(), values.getThirstMax())))
                .param("6", String.valueOf(config.getId()))
                .param("7", config.getTiming().getTimerBasis().toConfigValue())
                .param("8", String.valueOf(needs.getLastUpdateMs()))
                .param("9", String.valueOf(needs.getLastPassiveSweepMs()));
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
