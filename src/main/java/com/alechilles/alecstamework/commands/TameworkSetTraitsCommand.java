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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Replaces the targeted NPC's trait list with explicit trait/value pairs.
 */
public final class TameworkSetTraitsCommand extends AbstractPlayerCommand {
    public TameworkSetTraitsCommand() {
        super("traits", "Replace traits on the NPC you are looking at.");
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

        LinkedHashMap<String, TameworkTraitsComponent.TraitValue> deduped = new LinkedHashMap<>();
        ArrayList<String> notes = new ArrayList<>();
        for (TameworkTraitCommandInputParser.TraitRequest request : parseResult.requests()) {
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
            if (TameworkTraitCommandSupport.wasClamped(request.value(), applied)) {
                notes.add(definition.getId()
                        + " clamped to "
                        + TameworkTraitCommandSupport.formatDouble(applied));
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

        commandContext.sender().sendMessage(Message.raw(buildResultMessage(
                candidate.npcUuid.toString(),
                updated,
                notes
        )));
    }

    private static String buildResultMessage(String npcUuid,
                                             TameworkTraitsComponent traits,
                                             @Nullable ArrayList<String> notes) {
        StringBuilder message = new StringBuilder();
        message.append("Set traits for NPC ")
                .append(npcUuid)
                .append(": count=")
                .append(traits.getTraitValues().length)
                .append(", values=[");
        TameworkTraitsComponent.TraitValue[] values = traits.getTraitValues();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                message.append("; ");
            }
            TameworkTraitsComponent.TraitValue value = values[i];
            String id = value != null && value.getId() != null ? value.getId() : "unknown";
            double numeric = value != null ? value.getValue() : 0.0;
            message.append(id)
                    .append("=")
                    .append(TameworkTraitCommandSupport.formatDouble(numeric));
        }
        message.append("]");
        if (notes != null && !notes.isEmpty()) {
            message.append(", notes=").append(String.join(", ", notes));
        }
        message.append(".");
        return message.toString();
    }
}
