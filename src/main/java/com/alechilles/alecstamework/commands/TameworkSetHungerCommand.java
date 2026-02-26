package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Command to set hunger for the targeted NPC.
 */
public final class TameworkSetHungerCommand extends AbstractPlayerCommand {
    public TameworkSetHungerCommand() {
        super("sethunger", "Set hunger of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Double requested = TameworkNeedsCommandSupport.parseDoubleArg(commandContext.getInputString(), 2);
        if (requested == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw sethunger <value>"));
            return;
        }

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
        TameworkNeedsCommandSupport.NeedsContext context =
                TameworkNeedsCommandSupport.resolveContext(candidate.ref, store);
        if (context == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "No enabled needs config resolved for this NPC."
            ));
            return;
        }

        TameworkNeedsComponent needs = context.component();
        TwNeedsConfig.ValueSettings values = context.config() != null ? context.config().getValues() : null;
        double min = values != null ? values.getHungerMin() : 0.0;
        double max = values != null ? values.getHungerMax() : 100.0;
        double applied = TameworkNeedsCommandSupport.clamp(requested, min, max);
        long now = System.currentTimeMillis();
        needs.setHunger(applied);
        needs.setLastUpdateMs(now);
        needs.setLastPassiveSweepMs(now);
        store.putComponent(candidate.ref, needsType, needs);
        CompanionNeedsService.tickNeeds(candidate.ref, store, context.roleId());

        commandContext.sender().sendMessage(Message.raw(
                "Set hunger for NPC "
                        + candidate.npcUuid
                        + ": "
                        + TameworkNeedsCommandSupport.format(applied)
                        + " (requested "
                        + TameworkNeedsCommandSupport.format(requested)
                        + ")."
        ));
    }
}

