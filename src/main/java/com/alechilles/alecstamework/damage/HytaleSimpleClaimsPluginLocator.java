package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reads SimpleClaims presence and lifecycle directly from Hytale's PluginManager. */
final class HytaleSimpleClaimsPluginLocator implements SimpleClaimsPluginLocator {
    static final String PLUGIN_IDENTIFIER = "Buuz135:SimpleClaims";

    private final PluginIdentifier pluginIdentifier;
    private final Supplier<PluginManager> pluginManagerSupplier;

    HytaleSimpleClaimsPluginLocator() {
        this(PluginIdentifier.fromString(PLUGIN_IDENTIFIER), PluginManager::get);
    }

    HytaleSimpleClaimsPluginLocator(@Nonnull PluginIdentifier pluginIdentifier,
                                    @Nonnull Supplier<PluginManager> pluginManagerSupplier) {
        this.pluginIdentifier = Objects.requireNonNull(pluginIdentifier, "pluginIdentifier");
        this.pluginManagerSupplier = Objects.requireNonNull(pluginManagerSupplier, "pluginManagerSupplier");
    }

    @Nonnull
    @Override
    public SimpleClaimsPluginLocation locate() {
        try {
            PluginManager manager = pluginManagerSupplier.get();
            if (manager == null) {
                return unavailable(SimpleClaimsPluginState.NOT_READY, null, "PluginManager is not ready.");
            }
            PluginBase plugin = manager.getPlugin(pluginIdentifier);
            PluginManifest available = findManifest(manager.getAvailablePlugins());
            if (plugin == null) {
                return unavailable(
                        available == null ? SimpleClaimsPluginState.ABSENT : SimpleClaimsPluginState.NOT_READY,
                        versionOf(available),
                        available == null ? "SimpleClaims is not installed." : "SimpleClaims is not ready."
                );
            }
            SimpleClaimsPluginState state = mapState(plugin.getState());
            PluginManifest liveManifest = plugin.getManifest();
            return new SimpleClaimsPluginLocation(
                    state,
                    versionOf(liveManifest == null ? available : liveManifest),
                    state == SimpleClaimsPluginState.READY ? null : "Plugin state is " + plugin.getState() + ".",
                    generationOf(plugin),
                    plugin
            );
        } catch (RuntimeException | LinkageError exception) {
            return unavailable(
                    SimpleClaimsPluginState.ERROR,
                    null,
                    "PluginManager lookup failed: " + exception.getClass().getSimpleName()
            );
        }
    }

    @Nonnull
    static SimpleClaimsPluginState mapState(@Nullable PluginState state) {
        if (state == null) {
            return SimpleClaimsPluginState.NOT_READY;
        }
        return switch (state) {
            case ENABLED -> SimpleClaimsPluginState.READY;
            case DISABLED, SHUTDOWN -> SimpleClaimsPluginState.DISABLED;
            case FAILED -> SimpleClaimsPluginState.ERROR;
            case NONE, SETUP, START -> SimpleClaimsPluginState.NOT_READY;
        };
    }

    @Nullable
    private PluginManifest findManifest(@Nullable Map<PluginIdentifier, PluginManifest> manifests) {
        return manifests == null ? null : manifests.get(pluginIdentifier);
    }

    @Nonnull
    private static SimpleClaimsPluginGeneration generationOf(@Nonnull PluginBase plugin) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        return new SimpleClaimsPluginGeneration(
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

    @Nonnull
    private static SimpleClaimsPluginLocation unavailable(@Nonnull SimpleClaimsPluginState state,
                                                          @Nullable String version,
                                                          @Nonnull String reason) {
        return new SimpleClaimsPluginLocation(
                state,
                version,
                reason,
                SimpleClaimsPluginGeneration.NONE,
                null
        );
    }
}
