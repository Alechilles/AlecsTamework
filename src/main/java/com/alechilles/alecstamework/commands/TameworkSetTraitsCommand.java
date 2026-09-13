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
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Replaces the targeted NPC's trait list with explicit trait/value pairs.
 */
public final class TameworkSetTraitsCommand extends AbstractPlayerCommand {
    public TameworkSetTraitsCommand() {
        super("traits", "server.tamework.commands.setTraits.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkTraitCommandInputParser.ParseResult parseResult =
                TameworkTraitCommandInputParser.parseSetTraits(commandContext.getInputString());
        if (!parseResult.isSuccess()) {
            commandContext.sender().sendMessage(TameworkTraitCommandSupport.parseErrorMessage(parseResult.errorMessage()));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.no.npc.found.in.view"));
            return;
        }

        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.traits.component.is.not.available"));
            return;
        }
        TameworkTraitsComponent existing = store.getComponent(candidate.ref, traitsType);
        TwTraitConfig config = TameworkTraitCommandSupport.resolveTraitConfig(candidate.ref, store, existing);
        if (config == null || !config.isEnabled()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.no.enabled.trait.config.resolved.for.this"));
            return;
        }
        Map<String, TwTraitConfig.TraitDefinition> definitions = TameworkTraitCommandSupport.definitionMap(config);
        if (definitions.isEmpty()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.trait.config.has.no.trait.definitions").param("0", String.valueOf(config.getId())));
            return;
        }

        LinkedHashMap<String, TameworkTraitsComponent.TraitValue> deduped = new LinkedHashMap<>();
        boolean clampedAny = false;
        for (TameworkTraitCommandInputParser.TraitRequest request : parseResult.requests()) {
            String normalizedId = TameworkTraitCommandSupport.normalize(request.traitId());
            if (normalizedId == null) {
                commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.trait.id.is.invalid").param("0", String.valueOf(request.traitId())));
                return;
            }
            TwTraitConfig.TraitDefinition definition = definitions.get(normalizedId);
            if (definition == null) {
                commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.unknown.trait.known.traits").param("0", String.valueOf(request.traitId())).param("1", String.valueOf(TameworkTraitCommandSupport.buildKnownTraitsText(definitions))));
                return;
            }
            double applied = TameworkTraitCommandSupport.clampToBreedingRange(request.value(), definition);
            if (TameworkTraitCommandSupport.wasClamped(request.value(), applied)) {
                clampedAny = true;
            }
            deduped.put(normalizedId, new TameworkTraitsComponent.TraitValue(definition.getId(), applied));
        }

        TameworkTraitsComponent.TraitValue[] nextValues =
                deduped.values().toArray(new TameworkTraitsComponent.TraitValue[0]);
        long seed = TameworkTraitCommandSupport.resolveRollSeed(candidate.ref, store, existing);
        String configId = TameworkTraitCommandSupport.resolveConfigId(config, existing);

        TameworkTraitsComponent updated = new TameworkTraitsComponent(configId, seed, nextValues);
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

        String values = formatTraitValues(updated);
        if (!clampedAny) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.result")
                    .param("0", String.valueOf(candidate.npcUuid))
                    .param("1", String.valueOf(updated.getTraitValues().length))
                    .param("2", values));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTraits.result.clamped")
                .param("0", String.valueOf(candidate.npcUuid))
                .param("1", String.valueOf(updated.getTraitValues().length))
                .param("2", values));
    }

    @Nonnull
    private static String formatTraitValues(@Nonnull TameworkTraitsComponent traits) {
        StringBuilder values = new StringBuilder();
        for (TameworkTraitsComponent.TraitValue value : traits.getTraitValues()) {
            if (!values.isEmpty()) {
                values.append("; ");
            }
            String id = value != null && value.getId() != null ? value.getId() : "?";
            double numeric = value != null ? value.getValue() : 0.0;
            values.append(id).append("=").append(TameworkTraitCommandSupport.formatDouble(numeric));
        }
        return values.toString();
    }
}
