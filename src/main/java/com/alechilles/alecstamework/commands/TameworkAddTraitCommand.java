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
        super("trait", "server.tamework.commands.addTrait.description");
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
            commandContext.sender().sendMessage(TameworkTraitCommandSupport.parseErrorMessage(parseResult.errorMessage()));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.no.npc.found.in.view"));
            return;
        }

        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.traits.component.is.not.available"));
            return;
        }
        TameworkTraitsComponent existing = store.getComponent(candidate.ref, traitsType);
        TwTraitConfig config = TameworkTraitCommandSupport.resolveTraitConfig(candidate.ref, store, existing);
        if (config == null || !config.isEnabled()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.no.enabled.trait.config.resolved.for.this"));
            return;
        }
        Map<String, TwTraitConfig.TraitDefinition> definitions = TameworkTraitCommandSupport.definitionMap(config);
        if (definitions.isEmpty()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.trait.config.has.no.trait.definitions").param("0", String.valueOf(config.getId())));
            return;
        }

        TameworkTraitCommandInputParser.TraitRequest request = parseResult.requests().getFirst();
        String normalizedId = TameworkTraitCommandSupport.normalize(request.traitId());
        if (normalizedId == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.trait.id.is.invalid").param("0", String.valueOf(request.traitId())));
            return;
        }
        TwTraitConfig.TraitDefinition definition = definitions.get(normalizedId);
        if (definition == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.unknown.trait.known.traits").param("0", String.valueOf(request.traitId())).param("1", String.valueOf(TameworkTraitCommandSupport.buildKnownTraitsText(definitions))));
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

        if (TameworkTraitCommandSupport.wasClamped(request.value(), applied)) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.result.clamped")
                    .param("0", String.valueOf(candidate.npcUuid))
                    .param("1", definition.getId())
                    .param("2", TameworkTraitCommandSupport.formatDouble(applied))
                    .param("3", String.valueOf(updated.getTraitValues().length))
                    .param("4", TameworkTraitCommandSupport.formatDouble(request.value())));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.addTrait.result")
                .param("0", String.valueOf(candidate.npcUuid))
                .param("1", definition.getId())
                .param("2", TameworkTraitCommandSupport.formatDouble(applied))
                .param("3", String.valueOf(updated.getTraitValues().length)));
    }
}
