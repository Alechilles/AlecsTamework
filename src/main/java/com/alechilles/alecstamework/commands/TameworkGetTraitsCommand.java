package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to display rolled trait values for the targeted NPC.
 */
public final class TameworkGetTraitsCommand extends AbstractPlayerCommand {
    public TameworkGetTraitsCommand() {
        super("traits", "server.tamework.commands.getTraits.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getTraits.no.npc.found.in.view"));
            return;
        }

        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        if (type == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getTraits.traits.component.is.not.available"));
            return;
        }

        TameworkTraitsComponent traits = store.getComponent(candidate.ref, type);
        if (traits == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getTraits.npc.has.no.tracked.trait.state").param("0", String.valueOf(candidate.npcUuid)));
            return;
        }

        TwTraitConfig config = resolveTraitConfig(candidate.ref, store, traits);
        commandContext.sender().sendMessage(buildLocalizedMessage(candidate.npcUuid, traits, config));
    }

    @Nullable
    private static TwTraitConfig resolveTraitConfig(@Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store,
                                                    @Nullable TameworkTraitsComponent component) {
        if (component != null) {
            String configId = component.getConfigId();
            if (configId != null && !configId.isBlank()) {
                TwTraitConfig fromId = TwTraitConfig.resolveById(configId);
                if (fromId != null) {
                    return fromId;
                }
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwTraitConfig.resolveForRole(roleId);
    }

    @Nonnull
    private static Message buildLocalizedMessage(@Nonnull UUID npcUuid,
                                                 @Nonnull TameworkTraitsComponent traits,
                                                 @Nullable TwTraitConfig config) {
        String configId = normalizeBlank(traits.getConfigId());
        if (configId == null && config != null) {
            configId = normalizeBlank(config.getId());
        }
        TameworkTraitsComponent.TraitValue[] values = traits.getTraitValues().clone();
        Arrays.sort(values, TameworkGetTraitsCommand::compareTraitIds);
        if (values.length == 0) {
            return Message.translation("server.tamework.commands.getTraits.result.empty")
                    .param("0", String.valueOf(npcUuid))
                    .param("1", configId != null ? configId : "-")
                    .param("2", String.valueOf(traits.getRollSeed()));
        }
        return Message.translation("server.tamework.commands.getTraits.result")
                .param("0", String.valueOf(npcUuid))
                .param("1", configId != null ? configId : "-")
                .param("2", String.valueOf(traits.getRollSeed()))
                .param("3", String.valueOf(values.length))
                .param("4", formatTraitValues(values, definitionMap(config)));
    }

    @Nonnull
    private static String formatTraitValues(@Nonnull TameworkTraitsComponent.TraitValue[] values,
                                            @Nonnull Map<String, TwTraitConfig.TraitDefinition> definitions) {
        StringBuilder result = new StringBuilder();
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (!result.isEmpty()) {
                result.append("; ");
            }
            if (value == null || value.getId() == null || value.getId().isBlank()) {
                result.append("?=").append(formatDouble(0.0));
                continue;
            }
            String id = value.getId();
            result.append(id).append("=").append(formatDouble(value.getValue()));
            TwTraitConfig.TraitDefinition definition = definitions.get(id.trim().toLowerCase(Locale.ROOT));
            if (definition != null) {
                String effectKey = normalizeBlank(definition.getEffectKey());
                if (effectKey != null) {
                    result.append(" [").append(effectKey).append("]");
                }
            }
        }
        return result.toString();
    }

    private static int compareTraitIds(@Nullable TameworkTraitsComponent.TraitValue left,
                                       @Nullable TameworkTraitsComponent.TraitValue right) {
        String leftId = (left == null || left.getId() == null) ? "" : left.getId();
        String rightId = (right == null || right.getId() == null) ? "" : right.getId();
        return leftId.compareToIgnoreCase(rightId);
    }

    private static Map<String, TwTraitConfig.TraitDefinition> definitionMap(@Nullable TwTraitConfig config) {
        if (config == null) {
            return Map.of();
        }
        HashMap<String, TwTraitConfig.TraitDefinition> map = new HashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
                continue;
            }
            String normalized = definition.getId().trim().toLowerCase(Locale.ROOT);
            map.putIfAbsent(normalized, definition);
        }
        return map;
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
