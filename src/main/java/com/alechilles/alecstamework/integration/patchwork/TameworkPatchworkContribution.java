package com.alechilles.alecstamework.integration.patchwork;

import com.alechilles.patchwork.embedded.PatchworkHostContribution;
import com.alechilles.patchwork.embedded.PatchworkMacroProvider;
import com.alechilles.patchwork.embedded.PatchworkTargetAdapter;
import java.util.List;
import java.util.Objects;

/** Provides Tamework's stable Patchwork macro contract to the elected embedded runtime. */
final class TameworkPatchworkContribution implements PatchworkHostContribution {
    private static final String HOST_PLUGIN_IDENTIFIER = "Alechilles:Alec's Tamework!";
    private final String contributionVersion;
    private final List<PatchworkMacroProvider> macroProviders = List.of(
            new TameworkInteractionBridgeMacro(),
            new TameworkHookInstructionMacro(),
            new TameworkStateInstructionMacro()
    );

    TameworkPatchworkContribution(String contributionVersion) {
        this.contributionVersion = Objects.requireNonNull(contributionVersion, "contributionVersion");
    }

    @Override public String hostPluginIdentifier() { return HOST_PLUGIN_IDENTIFIER; }
    @Override public String contributionVersion() { return contributionVersion; }
    @Override public List<PatchworkMacroProvider> macroProviders() { return macroProviders; }
    @Override public List<PatchworkTargetAdapter> targetAdapters() { return List.of(); }
}
