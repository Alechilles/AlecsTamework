package com.alechilles.alecstamework.access.groups;

import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import java.lang.reflect.InvocationTargetException;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A sharing group backed by another mod, read by reflection so Tamework loads and runs without
 * that mod. The mod's classes are bound once per plugin instance, with the mod's own class loader.
 * Any failure answers {@link AccessGroupMembership#UNKNOWN}, which grants nothing: a mod that is
 * missing, still starting, or whose API changed never breaks Tamework's access checks.
 */
abstract class ModGroupProvider implements AccessGroupProvider {
    private static final System.Logger LOGGER = System.getLogger(ModGroupProvider.class.getName());

    private final String id;
    private final String displayNameKey;
    private final PluginIdentifier pluginId;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Bound bound;

    ModGroupProvider(@Nonnull String id, @Nonnull String displayNameKey, @Nonnull String pluginId) {
        this.id = id;
        this.displayNameKey = displayNameKey;
        this.pluginId = PluginIdentifier.fromString(pluginId);
    }

    /** The plugin this group reads, as {@code Group:Name} from its manifest. */
    @Nonnull
    final PluginIdentifier pluginId() {
        return pluginId;
    }

    @Nonnull
    @Override
    public final String id() {
        return id;
    }

    @Nonnull
    @Override
    public final String displayNameKey() {
        return displayNameKey;
    }

    @Nonnull
    @Override
    public final AccessGroupMembership membership(@Nonnull UUID ownerUuid, @Nonnull UUID actorUuid) {
        Lookup lookup = lookup();
        if (lookup == null) {
            return AccessGroupMembership.UNKNOWN;
        }
        try {
            return lookup.membership(ownerUuid, actorUuid);
        } catch (InvocationTargetException thrown) {
            if (!(thrown.getCause() instanceof ConcurrentModificationException)) {
                warnOnce("The mod failed while answering", thrown.getCause());
            }
            return AccessGroupMembership.UNKNOWN;
        } catch (ConcurrentModificationException busy) {
            // The mod changed its data while it was read here; the next check answers.
            return AccessGroupMembership.UNKNOWN;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            warnOnce("Could not read the mod's groups", failed);
            return AccessGroupMembership.UNKNOWN;
        }
    }

    /** Resolves the mod's classes and methods, loaded with {@code loader}. */
    @Nonnull
    abstract Lookup bind(@Nonnull ClassLoader loader) throws ReflectiveOperationException;

    @Nullable
    private Lookup lookup() {
        PluginBase plugin = enabledPlugin();
        if (plugin == null) {
            return null;
        }
        Bound current = bound;
        if (current == null || current.plugin() != plugin) {
            current = new Bound(plugin, bindSafely(plugin));
            bound = current;
        }
        return current.lookup();
    }

    @Nullable
    private PluginBase enabledPlugin() {
        PluginManager manager = PluginManager.get();
        PluginBase plugin = manager == null ? null : manager.getPlugin(pluginId);
        return plugin != null && plugin.getState() == PluginState.ENABLED ? plugin : null;
    }

    @Nullable
    private Lookup bindSafely(@Nonnull PluginBase plugin) {
        try {
            return bind(plugin.getClass().getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            warnOnce("This version of the mod is not supported", failed);
            return null;
        }
    }

    private void warnOnce(@Nonnull String what, @Nullable Throwable cause) {
        if (warned.compareAndSet(false, true)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    what + " for the sharing group " + id + " (" + pluginId
                            + "); the group grants nothing until this is fixed.", cause);
        }
    }

    @Nonnull
    static AccessGroupMembership member(boolean isMember) {
        return isMember ? AccessGroupMembership.MEMBER : AccessGroupMembership.NOT_MEMBER;
    }

    /** One membership question answered from the mod's in-memory data. */
    @FunctionalInterface
    interface Lookup {
        @Nonnull
        AccessGroupMembership membership(@Nonnull UUID ownerUuid, @Nonnull UUID actorUuid)
                throws ReflectiveOperationException;
    }

    private record Bound(PluginBase plugin, @Nullable Lookup lookup) {
    }
}
