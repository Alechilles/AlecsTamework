package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Appends one explicit trait/value pair to the targeted NPC's trait list.
 */
public final class TameworkAddTraitCommand extends AbstractPlayerCommand {
    public TameworkAddTraitCommand() {
        super("addtrait", "Append one trait to the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkTraitCommandInputParser.ParseResult parseResult =
                TameworkTraitCommandInputParser.parseAddTrait(commandContext.getInputString());
        if (!parseResult.isSuccess()) {
            commandContext.sender().sendMessage(Message.raw(parseResult.errorMessage()));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            commandContext.sender().sendMessage(Message.raw("Traits component is not available."));
            return;
        }
        TameworkTraitsComponent existing = store.getComponent(candidate.ref, traitsType);
        TwTraitConfig config = TameworkTraitCommandSupport.resolveTraitConfig(candidate.ref, store, existing);
        if (config == null || !config.isEnabled()) {
            commandContext.sender().sendMessage(Message.raw(
                    "No enabled trait config resolved for this NPC (role/config lookup failed)."
            ));
            return;
        }
        Map<String, TwTraitConfig.TraitDefinition> definitions = TameworkTraitCommandSupport.definitionMap(config);
        if (definitions.isEmpty()) {
            commandContext.sender().sendMessage(Message.raw(
                    "Trait config '" + config.getId() + "' has no trait definitions."
            ));
            return;
        }

        TameworkTraitCommandInputParser.TraitRequest request = parseResult.requests().getFirst();
        String normalizedId = TameworkTraitCommandSupport.normalize(request.traitId());
        if (normalizedId == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "Trait id '" + request.traitId() + "' is invalid."
            ));
            return;
        }
        TwTraitConfig.TraitDefinition definition = definitions.get(normalizedId);
        if (definition == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "Unknown trait '" + request.traitId() + "'. Known traits: "
                            + TameworkTraitCommandSupport.buildKnownTraitsText(definitions)
            ));
            return;
        }

        double applied = TameworkTraitCommandSupport.clampToBreedingRange(request.value(), definition);
        TameworkTraitsComponent.TraitValue nextValue =
                new TameworkTraitsComponent.TraitValue(definition.getId(), applied);
        TameworkTraitsComponent.TraitValue[] baseValues = existing != null
                ? existing.getTraitValues()
                : new TameworkTraitsComponent.TraitValue[0];
        TameworkTraitsComponent.TraitValue[] combined = Arrays.copyOf(baseValues, baseValues.length + 1);
        combined[combined.length - 1] = nextValue;

        long seed = TameworkTraitCommandSupport.resolveRollSeed(candidate.ref, store, existing);
        String configId = TameworkTraitCommandSupport.resolveConfigId(config, existing);
        TameworkTraitsComponent updated = new TameworkTraitsComponent(configId, seed, combined);
        double previousSizeMultiplier = TraitModifierService.resolveMultiplier(
                existing,
                config,
                "SizeMultiplier",
                1.0
        );
        double nextSizeMultiplier = TraitModifierService.resolveMultiplier(
                updated,
                config,
                "SizeMultiplier",
                1.0
        );
        store.putComponent(candidate.ref, traitsType, updated);
        CompanionStatModifierService.applyTraitModifiers(candidate.ref, store);
        CompanionLifeStageService.applySizeMultiplierDelta(
                candidate.ref,
                store,
                previousSizeMultiplier,
                nextSizeMultiplier
        );

        StringBuilder message = new StringBuilder();
        message.append("Added trait for NPC ")
                .append(candidate.npcUuid)
                .append(": ")
                .append(definition.getId())
                .append("=")
                .append(TameworkTraitCommandSupport.formatDouble(applied))
                .append(", count=")
                .append(updated.getTraitValues().length);
        if (TameworkTraitCommandSupport.wasClamped(request.value(), applied)) {
            message.append(" (requested ")
                    .append(TameworkTraitCommandSupport.formatDouble(request.value()))
                    .append(", clamped to breeding range)");
        }
        message.append(".");
        commandContext.sender().sendMessage(Message.raw(message.toString()));
    }
}
