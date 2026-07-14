package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.commands.NPCMultiSelectCommandBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.DOUBLE;

/**
 * Sets hunger and thirst for NPCs selected with the standard NPC debug selectors.
 */
public final class TameworkSetNeedsCommand extends NPCMultiSelectCommandBase {
    private final RequiredArg<Double> hungerArg = withRequiredArg(
            "hunger", "Hunger value to apply.", DOUBLE
    );
    private final RequiredArg<Double> thirstArg = withRequiredArg(
            "thirst", "Thirst value to apply.", DOUBLE
    );

    public TameworkSetNeedsCommand() {
        super("needs", "Set hunger and thirst for selected NPCs.");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            context.sendMessage(Message.raw("Needs component type is not registered."));
            return;
        }
        TameworkNeedsCommandSupport.NeedsContext needsContext =
                TameworkNeedsCommandSupport.resolveContext(npcRef, store);
        if (needsContext == null) {
            context.sendMessage(Message.raw("No enabled needs config resolved for NPC " + npc.getUuid() + "."));
            return;
        }

        double requestedHunger = hungerArg.get(context);
        double requestedThirst = thirstArg.get(context);
        TwNeedsConfig.ValueSettings values = needsContext.config() != null ? needsContext.config().getValues() : null;
        double hunger = TameworkNeedsCommandSupport.clamp(
                requestedHunger, values != null ? values.getHungerMin() : 0.0,
                values != null ? values.getHungerMax() : 100.0
        );
        double thirst = TameworkNeedsCommandSupport.clamp(
                requestedThirst, values != null ? values.getThirstMin() : 0.0,
                values != null ? values.getThirstMax() : 100.0
        );

        TameworkNeedsComponent needs = needsContext.component();
        long now = System.currentTimeMillis();
        needs.setHunger(hunger);
        needs.setThirst(thirst);
        needs.setLastUpdateMs(now);
        needs.setLastPassiveSweepMs(now);
        store.putComponent(npcRef, needsType, needs);
        CompanionNeedsService.tickNeeds(npcRef, store, needsContext.roleId());
        context.sendMessage(Message.raw(
                "Set needs for NPC " + npc.getUuid() + ": hunger="
                        + TameworkNeedsCommandSupport.format(hunger)
                        + ", thirst=" + TameworkNeedsCommandSupport.format(thirst) + "."
        ));
    }
}
