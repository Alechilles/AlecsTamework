package com.alechilles.alecstamework.integration.claims;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maps optional claim-plugin setup/reload events to their lifecycle-aware cache invalidators.
 *
 * <p>Hytale 0.5.6 emits {@link PluginSetupEvent} for successful load and reload setup, but does
 * not emit an equivalent unload event. Provider caches therefore also use weak positive entries
 * and observe PluginManager on every operation.</p>
 */
public final class ClaimProviderLifecycleInvalidator {
    private final Consumer<ClaimIntegrationProvider> invalidator;

    public ClaimProviderLifecycleInvalidator(
            @Nonnull Consumer<ClaimIntegrationProvider> invalidator) {
        this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
    }

    /** Invalidates only the optional claim provider represented by the completed setup event. */
    public void onPluginSetup(@Nullable PluginSetupEvent event) {
        if (event == null) {
            return;
        }
        PluginBase plugin = event.getPlugin();
        if (plugin == null || plugin.getManifest() == null) {
            return;
        }
        try {
            onPluginSetupIdentifier(new PluginIdentifier(plugin.getManifest()).toString());
        } catch (RuntimeException | LinkageError ignored) {
            // A malformed unrelated manifest must not interrupt the server's plugin setup sequence.
        }
    }

    void onPluginSetupIdentifier(@Nullable String pluginIdentifier) {
        ClaimIntegrationProvider provider = providerFor(pluginIdentifier);
        if (provider != null) {
            invalidator.accept(provider);
        }
    }

    @Nullable
    static ClaimIntegrationProvider providerFor(@Nullable String pluginIdentifier) {
        if (HytaleClaimPluginLocator.QUESTLINES_PLUGIN_IDENTIFIER.equals(pluginIdentifier)) {
            return ClaimIntegrationProvider.QUESTLINES_CLAIMS;
        }
        if (HytaleClaimPluginLocator.SIMPLE_CLAIMS_PLUGIN_IDENTIFIER.equals(pluginIdentifier)) {
            return ClaimIntegrationProvider.SIMPLE_CLAIMS;
        }
        return null;
    }
}
