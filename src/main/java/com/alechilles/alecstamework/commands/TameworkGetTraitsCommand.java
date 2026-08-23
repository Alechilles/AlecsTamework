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
        super("traits", "Get traits of the NPC you are looking at.");
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
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        if (type == null) {
            commandContext.sender().sendMessage(Message.raw("Traits component is not available."));
            return;
        }

        TameworkTraitsComponent traits = store.getComponent(candidate.ref, type);
        if (traits == null) {
            commandContext.sender().sendMessage(Message.raw("NPC " + candidate.npcUuid + " has no tracked trait state."));
            return;
        }

        TwTraitConfig config = resolveTraitConfig(candidate.ref, store, traits);
        commandContext.sender().sendMessage(Message.raw(buildMessage(candidate.npcUuid, traits, config)));
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

    private static String buildMessage(@Nonnull UUID npcUuid,
                                       @Nonnull TameworkTraitsComponent traits,
                                       @Nullable TwTraitConfig config) {
        StringBuilder message = new StringBuilder();
        message.append("Traits for NPC ").append(npcUuid).append(": ");

        String configId = normalizeBlank(traits.getConfigId());
        if (configId == null && config != null) {
            configId = normalizeBlank(config.getId());
        }
        message.append("config=").append(configId != null ? configId : "n/a");
        message.append(", seed=").append(traits.getRollSeed());

        TameworkTraitsComponent.TraitValue[] values = traits.getTraitValues().clone();
        Arrays.sort(values, (left, right) -> compareTraitIds(left, right));

        if (values.length == 0) {
            message.append(", count=0.");
            return message.toString();
        }

        message.append(", count=").append(values.length).append(", values=[");
        Map<String, TwTraitConfig.TraitDefinition> definitions = definitionMap(config);
        for (int i = 0; i < values.length; i++) {
            TameworkTraitsComponent.TraitValue value = values[i];
            if (i > 0) {
                message.append("; ");
            }
            appendTraitValue(message, value, definitions);
        }
        message.append("].");
        return message.toString();
    }

    private static int compareTraitIds(@Nullable TameworkTraitsComponent.TraitValue left,
                                       @Nullable TameworkTraitsComponent.TraitValue right) {
        String leftId = (left == null || left.getId() == null) ? "" : left.getId();
        String rightId = (right == null || right.getId() == null) ? "" : right.getId();
        return leftId.compareToIgnoreCase(rightId);
    }

    private static void appendTraitValue(StringBuilder message,
                                         @Nullable TameworkTraitsComponent.TraitValue value,
                                         Map<String, TwTraitConfig.TraitDefinition> definitions) {
        if (value == null || value.getId() == null || value.getId().isBlank()) {
            message.append("unknown=").append(formatDouble(0.0));
            return;
        }
        String id = value.getId();
        String normalizedId = id.trim().toLowerCase(Locale.ROOT);
        TwTraitConfig.TraitDefinition definition = definitions.get(normalizedId);

        message.append(id)
                .append("=")
                .append(formatDouble(value.getValue()));
        if (definition == null) {
            return;
        }
        String effectKey = normalizeBlank(definition.getEffectKey());
        if (effectKey != null) {
            message.append(" (effect=").append(effectKey).append(")");
        }
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
