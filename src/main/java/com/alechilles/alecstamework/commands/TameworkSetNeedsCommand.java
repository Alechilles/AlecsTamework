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
 * Command to set both hunger and thirst values for the targeted NPC.
 */
public final class TameworkSetNeedsCommand extends AbstractPlayerCommand {
    public TameworkSetNeedsCommand() {
        super("setneeds", "Set hunger and thirst of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Double requestedHunger = TameworkNeedsCommandSupport.parseDoubleArg(commandContext.getInputString(), 2);
        Double requestedThirst = TameworkNeedsCommandSupport.parseDoubleArg(commandContext.getInputString(), 3);
        if (requestedHunger == null || requestedThirst == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw setneeds <hunger> <thirst>"));
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
        double minHunger = values != null ? values.getHungerMin() : 0.0;
        double maxHunger = values != null ? values.getHungerMax() : 100.0;
        double minThirst = values != null ? values.getThirstMin() : 0.0;
        double maxThirst = values != null ? values.getThirstMax() : 100.0;
        double appliedHunger = TameworkNeedsCommandSupport.clamp(requestedHunger, minHunger, maxHunger);
        double appliedThirst = TameworkNeedsCommandSupport.clamp(requestedThirst, minThirst, maxThirst);
        long now = System.currentTimeMillis();
        needs.setHunger(appliedHunger);
        needs.setThirst(appliedThirst);
        needs.setLastUpdateMs(now);
        needs.setLastPassiveSweepMs(now);
        store.putComponent(candidate.ref, needsType, needs);
        CompanionNeedsService.tickNeeds(candidate.ref, store, context.roleId());

        commandContext.sender().sendMessage(Message.raw(
                "Set needs for NPC "
                        + candidate.npcUuid
                        + ": hunger="
                        + TameworkNeedsCommandSupport.format(appliedHunger)
                        + " (requested "
                        + TameworkNeedsCommandSupport.format(requestedHunger)
                        + "), thirst="
                        + TameworkNeedsCommandSupport.format(appliedThirst)
                        + " (requested "
                        + TameworkNeedsCommandSupport.format(requestedThirst)
                        + ")."
        ));
    }
}
