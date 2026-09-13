package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared helpers for trait mutation/debug commands.
 */
final class TameworkTraitCommandSupport {
    private static final double EPSILON = 0.000001;

    private TameworkTraitCommandSupport() {
    }

    @Nullable
    static TwTraitConfig resolveTraitConfig(@Nullable Ref<EntityStore> npcRef,
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
    static Map<String, TwTraitConfig.TraitDefinition> definitionMap(@Nullable TwTraitConfig config) {
        if (config == null) {
            return Map.of();
        }
        HashMap<String, TwTraitConfig.TraitDefinition> map = new HashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
                continue;
            }
            String normalized = normalize(definition.getId());
            if (normalized == null) {
                continue;
            }
            map.putIfAbsent(normalized, definition);
        }
        return map;
    }

    @Nullable
    static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static double clampToBreedingRange(double requested, @Nullable TwTraitConfig.TraitDefinition definition) {
        if (definition == null || !Double.isFinite(requested)) {
            return requested;
        }
        double min = definition.getBreedingMin();
        double max = definition.getBreedingMax();
        if (!Double.isFinite(min)) {
            min = definition.getDefaultValue();
        }
        if (!Double.isFinite(max)) {
            max = definition.getDefaultValue();
        }
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        return clamp(requested, min, max);
    }

    static boolean wasClamped(double requested, double applied) {
        return Double.isFinite(requested)
                && Double.isFinite(applied)
                && Math.abs(requested - applied) > EPSILON;
    }

    static long resolveRollSeed(@Nullable Ref<EntityStore> npcRef,
                                @Nullable Store<EntityStore> store,
                                @Nullable TameworkTraitsComponent existing) {
        if (existing != null && existing.getRollSeed() != 0L) {
            return existing.getRollSeed();
        }
        if (npcRef != null && npcRef.isValid() && store != null) {
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc != null && npc.getUuid() != null) {
                UUID uuid = npc.getUuid();
                long seed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
                if (seed != 0L) {
                    return seed;
                }
            }
        }
        long fallback = System.nanoTime();
        return fallback != 0L ? fallback : 1L;
    }

    @Nullable
    static String resolveConfigId(@Nullable TwTraitConfig config,
                                  @Nullable TameworkTraitsComponent existing) {
        if (config != null && config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (existing != null && existing.getConfigId() != null && !existing.getConfigId().isBlank()) {
            return existing.getConfigId();
        }
        return null;
    }

    static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @Nonnull
    static String buildKnownTraitsText(@Nonnull Map<String, TwTraitConfig.TraitDefinition> definitionMap) {
        if (definitionMap.isEmpty()) {
            return "-";
        }
        return definitionMap.values().stream()
                .map(TwTraitConfig.TraitDefinition::getId)
                .filter(id -> id != null && !id.isBlank())
                .sorted(String::compareToIgnoreCase)
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    @Nonnull
    static Message parseErrorMessage(@Nullable String errorMessage) {
        if (errorMessage == null) {
            return Message.translation("server.tamework.commands.traits.invalid.input");
        }
        if (errorMessage.startsWith("Usage: /tw settraits")) {
            return Message.translation("server.tamework.commands.traits.usage.setTraits");
        }
        if (errorMessage.startsWith("Usage: /tw addtrait")) {
            return Message.translation("server.tamework.commands.traits.usage.addTrait");
        }
        if (errorMessage.startsWith("Trait id at argument ") && errorMessage.endsWith(" is empty.")) {
            String index = errorMessage.substring("Trait id at argument ".length(), errorMessage.length() - " is empty.".length());
            return Message.translation("server.tamework.commands.traits.trait.id.empty").param("0", index);
        }
        if (errorMessage.startsWith("Value for trait '") && errorMessage.contains("' is invalid.")) {
            int end = errorMessage.indexOf("' is invalid.", "Value for trait '".length());
            if (end > "Value for trait '".length()) {
                String traitId = errorMessage.substring("Value for trait '".length(), end);
                return Message.translation("server.tamework.commands.traits.trait.value.invalid")
                        .param("0", traitId);
            }
        }
        if ("No trait values were provided.".equals(errorMessage)) {
            return Message.translation("server.tamework.commands.traits.values.missing");
        }
        return Message.translation("server.tamework.commands.traits.invalid.input");
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}

