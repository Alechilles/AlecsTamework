package com.alechilles.alecstamework.integration.claims;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads optional-plugin presence and lifecycle directly from Hytale's 0.5.6 PluginManager API.
 * The locator does not cache plugin or classloader objects.
 */
public final class HytaleClaimPluginLocator implements ClaimPluginLocator {
    public static final String QUESTLINES_PLUGIN_IDENTIFIER = "net.evilcraft:QuestLinesClaims";

    private final String providerId;
    private final PluginIdentifier pluginIdentifier;
    private final Supplier<PluginManager> pluginManagerSupplier;

    public HytaleClaimPluginLocator(@Nonnull String providerId, @Nonnull String pluginIdentifier) {
        this(providerId, PluginIdentifier.fromString(pluginIdentifier), PluginManager::get);
    }

    HytaleClaimPluginLocator(@Nonnull String providerId,
                             @Nonnull PluginIdentifier pluginIdentifier,
                             @Nonnull Supplier<PluginManager> pluginManagerSupplier) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required.");
        }
        if (pluginIdentifier == null) {
            throw new IllegalArgumentException("Plugin identifier is required.");
        }
        if (pluginManagerSupplier == null) {
            throw new IllegalArgumentException("PluginManager supplier is required.");
        }
        this.providerId = providerId;
        this.pluginIdentifier = pluginIdentifier;
        this.pluginManagerSupplier = pluginManagerSupplier;
    }

    @Nonnull
    @Override
    public ClaimPluginLocation locate() {
        try {
            PluginManager manager = pluginManagerSupplier.get();
            if (manager == null) {
                return unavailable(
                        ClaimProviderState.NOT_READY,
                        null,
                        "Hytale PluginManager is not ready."
                );
            }

            PluginBase plugin = manager.getPlugin(pluginIdentifier);
            PluginManifest availableManifest = findAvailableManifest(manager.getAvailablePlugins());
            if (plugin == null) {
                boolean installed = availableManifest != null;
                ClaimProviderState state = mapLifecycleState(installed, null);
                String reason = installed
                        ? "Plugin manifest is installed but no live plugin instance is ready."
                        : "Plugin is not installed.";
                return unavailable(state, versionOf(availableManifest), reason);
            }

            PluginState lifecycleState = plugin.getState();
            ClaimProviderState state = mapLifecycleState(true, lifecycleState);
            PluginManifest liveManifest = plugin.getManifest();
            String version = versionOf(liveManifest == null ? availableManifest : liveManifest);
            ClaimProviderGeneration generation = generationOf(plugin);
            return new ClaimPluginLocation(
                    providerId,
                    state,
                    version,
                    reasonFor(state, lifecycleState),
                    generation,
                    plugin
            );
        } catch (RuntimeException | LinkageError exception) {
            return unavailable(
                    ClaimProviderState.ERROR,
                    null,
                    "PluginManager lookup failed: " + exception.getClass().getSimpleName()
            );
        }
    }

    /**
     * Converts Hytale lifecycle state without relying on the broader {@code isEnabled()} helper.
     */
    @Nonnull
    static ClaimProviderState mapLifecycleState(boolean manifestAvailable,
                                                @Nullable PluginState liveState) {
        if (liveState == null) {
            return manifestAvailable ? ClaimProviderState.NOT_READY : ClaimProviderState.ABSENT;
        }
        return switch (liveState) {
            case ENABLED -> ClaimProviderState.READY;
            case DISABLED, SHUTDOWN -> ClaimProviderState.DISABLED;
            case FAILED -> ClaimProviderState.ERROR;
            case NONE, SETUP, START -> ClaimProviderState.NOT_READY;
        };
    }

    @Nullable
    private PluginManifest findAvailableManifest(@Nullable Map<PluginIdentifier, PluginManifest> availablePlugins) {
        return availablePlugins == null ? null : availablePlugins.get(pluginIdentifier);
    }

    @Nonnull
    private ClaimPluginLocation unavailable(@Nonnull ClaimProviderState state,
                                            @Nullable String version,
                                            @Nonnull String reason) {
        return new ClaimPluginLocation(
                providerId,
                state,
                version,
                reason,
                ClaimProviderGeneration.NONE,
                null
        );
    }

    @Nonnull
    private static ClaimProviderGeneration generationOf(@Nonnull PluginBase plugin) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        return new ClaimProviderGeneration(
                identityToken(plugin),
                classLoader == null ? "bootstrap" : identityToken(classLoader),
                0L
        );
    }

    @Nonnull
    private static String identityToken(@Nonnull Object value) {
        return value.getClass().getName()
                + "@"
                + Integer.toUnsignedString(System.identityHashCode(value), 16);
    }

    @Nullable
    private static String versionOf(@Nullable PluginManifest manifest) {
        return manifest == null || manifest.getVersion() == null ? null : manifest.getVersion().toString();
    }

    @Nullable
    private static String reasonFor(@Nonnull ClaimProviderState state,
                                    @Nullable PluginState lifecycleState) {
        return switch (state) {
            case READY -> null;
            case DISABLED -> "Plugin lifecycle state is " + lifecycleState + ".";
            case NOT_READY -> "Plugin lifecycle state is " + lifecycleState + ".";
            case ERROR -> "Plugin lifecycle state is " + lifecycleState + ".";
            default -> "Plugin is unavailable (" + state + ").";
        };
    }
}
