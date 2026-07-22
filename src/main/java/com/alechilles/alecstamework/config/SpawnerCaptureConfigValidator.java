package com.alechilles.alecstamework.config;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Pure cross-field validation for capture result and source-consumption settings. */
public final class SpawnerCaptureConfigValidator {
    private SpawnerCaptureConfigValidator() {
    }

    @Nonnull
    public static List<String> validate(@Nonnull TwSpawnerConfig source,
                                        @Nonnull ItemFeatureConfig compiled) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(compiled, "compiled");
        String configId = normalize(source.getId());
        String owner = configId == null ? "<missing>" : configId;
        ItemFeatureConfig.CaptureItemMechanics mechanics = compiled.getCaptureMechanics();
        List<String> errors = new ArrayList<>();

        if (mechanics.successDisposition() == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            if (!compiled.isCaptureTamesTarget()) {
                errors.add("capture-tame-link-requires-tames-target:" + owner);
            }
            if (compiled.getCaptureTamedRoleOverrides().isEmpty()
                    || compiled.getCaptureTamedRoleOverrides().entrySet().stream().anyMatch(entry ->
                    normalize(entry.getKey()) == null || normalize(entry.getValue()) == null)) {
                errors.add("capture-tame-link-requires-valid-tamed-role-result:" + owner);
            }
            if (source.isFilledItemIdExplicit() && normalize(source.getFilledItemId()) != null) {
                errors.add("capture-tame-link-contradicts-explicit-filled-item:" + owner);
            }
        }
        return List.copyOf(errors);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
