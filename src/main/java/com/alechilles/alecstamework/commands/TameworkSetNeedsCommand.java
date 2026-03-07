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
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Command to set both hunger and thirst values for the targeted NPC.
 */
public final class TameworkSetNeedsCommand extends AbstractPlayerCommand {
    private static final String AOE_TOKEN = "aoe";

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
        String input = commandContext.getInputString();
        Double requestedHunger = TameworkNeedsCommandSupport.parseDoubleArg(commandContext.getInputString(), 2);
        Double requestedThirst = TameworkNeedsCommandSupport.parseDoubleArg(commandContext.getInputString(), 3);
        if (requestedHunger == null || requestedThirst == null) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw setneeds <hunger> <thirst> [aoe <radius>]"));
            return;
        }
        boolean applyAoe = false;
        double aoeRadius = 0.0;
        String[] tokens = input == null ? new String[0] : input.trim().split("\\s+");
        if (tokens.length > 4) {
            if (tokens.length != 6 || !AOE_TOKEN.equalsIgnoreCase(tokens[4])) {
                commandContext.sender().sendMessage(Message.raw("Usage: /tw setneeds <hunger> <thirst> [aoe <radius>]"));
                return;
            }
            Double parsedRadius = TameworkNeedsCommandSupport.parseDoubleArg(input, 5);
            if (parsedRadius == null || parsedRadius <= 0.0) {
                commandContext.sender().sendMessage(Message.raw("AOE radius must be a positive number."));
                return;
            }
            applyAoe = true;
            aoeRadius = parsedRadius;
        }

        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            commandContext.sender().sendMessage(Message.raw("Needs component type is not registered."));
            return;
        }
        if (applyAoe) {
            List<TameworkCommandTargeting.Candidate> candidates =
                    TameworkCommandTargeting.findNearbyNpcs(store, ref, aoeRadius);
            if (candidates.isEmpty()) {
                commandContext.sender().sendMessage(Message.raw("No NPCs found within radius " + aoeRadius + "."));
                return;
            }
            int updated = 0;
            int skippedNoConfig = 0;
            for (TameworkCommandTargeting.Candidate candidate : candidates) {
                if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
                    continue;
                }
                TameworkNeedsCommandSupport.NeedsContext context =
                        TameworkNeedsCommandSupport.resolveContext(candidate.ref, store);
                if (context == null) {
                    skippedNoConfig++;
                    continue;
                }
                applyNeeds(
                        candidate.ref,
                        context,
                        needsType,
                        store,
                        requestedHunger,
                        requestedThirst
                );
                updated++;
            }
            commandContext.sender().sendMessage(Message.raw(
                    "Set needs in AOE radius "
                            + TameworkNeedsCommandSupport.format(aoeRadius)
                            + ": updated="
                            + updated
                            + ", skippedNoConfig="
                            + skippedNoConfig
                            + ", requestedHunger="
                            + TameworkNeedsCommandSupport.format(requestedHunger)
                            + ", requestedThirst="
                            + TameworkNeedsCommandSupport.format(requestedThirst)
                            + "."
            ));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
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

        AppliedValues appliedValues = applyNeeds(
                candidate.ref,
                context,
                needsType,
                store,
                requestedHunger,
                requestedThirst
        );

        commandContext.sender().sendMessage(Message.raw(
                "Set needs for NPC "
                        + candidate.npcUuid
                        + ": hunger="
                        + TameworkNeedsCommandSupport.format(appliedValues.hunger())
                        + " (requested "
                        + TameworkNeedsCommandSupport.format(requestedHunger)
                        + "), thirst="
                        + TameworkNeedsCommandSupport.format(appliedValues.thirst())
                        + " (requested "
                        + TameworkNeedsCommandSupport.format(requestedThirst)
                        + ")."
        ));
    }

    @Nonnull
    private static AppliedValues applyNeeds(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull TameworkNeedsCommandSupport.NeedsContext context,
                                            @Nonnull ComponentType<EntityStore, TameworkNeedsComponent> needsType,
                                            @Nonnull Store<EntityStore> store,
                                            double requestedHunger,
                                            double requestedThirst) {
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
        store.putComponent(npcRef, needsType, needs);
        CompanionNeedsService.tickNeeds(npcRef, store, context.roleId());
        return new AppliedValues(appliedHunger, appliedThirst);
    }

    private record AppliedValues(double hunger, double thirst) {
    }
}
