package com.alechilles.alecstamework.integration.patchwork;

import com.alechilles.patchwork.embedded.PatchworkHostContribution;
import com.alechilles.patchwork.embedded.PatchworkMacroProvider;
import com.alechilles.patchwork.embedded.PatchworkTargetAdapter;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Provides Tamework's stable Patchwork macro contract and mod setting values to the elected embedded runtime. */
final class TameworkPatchworkContribution implements PatchworkHostContribution {
    private static final String HOST_PLUGIN_IDENTIFIER = "Alechilles:Alec's Tamework!";
    private final String contributionVersion;
    private final List<PatchworkMacroProvider> macroProviders = List.of(
            new TameworkInteractionBridgeMacro(),
            new TameworkHookInstructionMacro(),
            new TameworkStateInstructionMacro()
    );

    private final Supplier<Map<String, JsonElement>> settingValues;

    TameworkPatchworkContribution(String contributionVersion) {
        this(contributionVersion, Map::of);
    }

    TameworkPatchworkContribution(String contributionVersion, Supplier<Map<String, JsonElement>> settingValues) {
        this.contributionVersion = Objects.requireNonNull(contributionVersion, "contributionVersion");
        this.settingValues = Objects.requireNonNull(settingValues, "settingValues");
    }

    @Override public String hostPluginIdentifier() { return HOST_PLUGIN_IDENTIFIER; }
    @Override public String contributionVersion() { return contributionVersion; }
    @Override public List<PatchworkMacroProvider> macroProviders() { return macroProviders; }
    @Override public List<PatchworkTargetAdapter> targetAdapters() { return List.of(); }
    @Override public Map<String, JsonElement> settingValues() { return settingValues.get(); }
}
