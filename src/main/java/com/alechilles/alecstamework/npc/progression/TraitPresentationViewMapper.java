package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps stored or live trait values into detached presentation data. */
public final class TraitPresentationViewMapper {
    private TraitPresentationViewMapper() {
    }

    @Nonnull
    public static ProgressionView.TraitsView map(
            @Nonnull TameworkTraitsComponent traits,
            @Nullable TwTraitConfig config
    ) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<>();
        for (TameworkTraitsComponent.TraitValue value : traits.getTraitValues()) {
            if (value == null || value.getId() == null || value.getId().isBlank()
                    || !Double.isFinite(value.getValue())) continue;
            values.putIfAbsent(value.getId().trim(), value.getValue());
        }
        return map(traits.getConfigId(), traits.getRollSeed(), values, config);
    }

    /** Maps immutable saved values without accessing a live entity or component. */
    @Nonnull
    public static ProgressionView.TraitsView map(
            @Nullable String configId,
            long rollSeed,
            @Nullable Map<String, Double> rawValues,
            @Nullable TwTraitConfig config
    ) {
        Map<String, TwTraitConfig.TraitDefinition> definitions = definitions(config);
        List<ProgressionView.TraitValueView> values = new ArrayList<>();
        if (rawValues != null) {
            rawValues.forEach((id, value) -> {
                if (id == null || id.isBlank() || value == null
                        || !Double.isFinite(value)) return;
                TwTraitConfig.TraitDefinition definition = definitions.get(
                        id.trim().toLowerCase(Locale.ROOT));
                values.add(value(id.trim(), value, definition));
            });
        }
        return new ProgressionView.TraitsView(configId, rollSeed, List.copyOf(values));
    }

    @Nonnull
    private static Map<String, TwTraitConfig.TraitDefinition> definitions(
            @Nullable TwTraitConfig config
    ) {
        if (config == null) return Map.of();
        LinkedHashMap<String, TwTraitConfig.TraitDefinition> result = new LinkedHashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null || definition.getId() == null
                    || definition.getId().isBlank()) continue;
            result.putIfAbsent(definition.getId().trim().toLowerCase(Locale.ROOT), definition);
        }
        return result;
    }

    @Nonnull
    private static ProgressionView.TraitValueView value(
            @Nonnull String id,
            double value,
            @Nullable TwTraitConfig.TraitDefinition definition
    ) {
        if (definition == null) return new ProgressionView.TraitValueView(id, value, null);
        return new ProgressionView.TraitValueView(
                id, value, definition.getEffectKey(), definition.getDefaultValue(),
                definition.getBreedingMin(), definition.getBreedingMax(),
                TraitModifierService.resolveMeritDirection(definition.getEffectKey()),
                TraitModifierService.resolveSignedMerit(definition, value),
                TraitModifierService.resolveSizeMeatHideYieldBonus(definition, value));
    }
}
