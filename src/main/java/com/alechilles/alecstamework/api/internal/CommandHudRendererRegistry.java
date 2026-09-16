package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudRendererProvider;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe exact-generation registry for target and hotswap HUD renderers. */
public final class CommandHudRendererRegistry implements AutoCloseable {
    private final CommandHudRegistrationStore<CommandHudRendererId, CommandHudRendererDescriptor>
            registrations = new CommandHudRegistrationStore<>(CommandHudRendererId::value);

    /** Registers a target renderer with an unrestricted descriptor. */
    /** Registers a target renderer and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId, @Nullable CommandTargetHudRendererProvider provider
    ) { return registerTarget(rawId, CommandHudRendererDescriptor.unrestricted(), provider); }

    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId, @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return register(CommandHudSurface.TARGET, rawId, descriptor, provider,
                "Command HUD target renderer provider is required.");
    }

    /** Registers a hotswap renderer with an unrestricted descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId, @Nullable CommandHotswapHudRendererProvider provider
    ) { return registerHotswap(rawId, CommandHudRendererDescriptor.unrestricted(), provider); }

    /** Registers a hotswap renderer and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId, @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return register(CommandHudSurface.HOTSWAP, rawId, descriptor, provider,
                "Command HUD hotswap renderer provider is required.");
    }

    /** Finds the currently active target renderer provider. */
    @Nonnull
    public synchronized Optional<CommandTargetHudRendererProvider> findTarget(@Nullable String rawId) {
        return resolve(CommandHudSurface.TARGET, rawId)
                .map(entry -> (CommandTargetHudRendererProvider) entry.provider());
    }

    /** Finds the currently active hotswap renderer provider. */
    @Nonnull
    public synchronized Optional<CommandHotswapHudRendererProvider> findHotswap(@Nullable String rawId) {
        return resolve(CommandHudSurface.HOTSWAP, rawId)
                .map(entry -> (CommandHotswapHudRendererProvider) entry.provider());
    }

    /** Resolves a target renderer and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedTargetRenderer> resolveTarget(@Nullable String rawId) {
        return resolve(CommandHudSurface.TARGET, rawId).map(entry -> new ResolvedTargetRenderer(
                entry.registrationId(), (CommandTargetHudRendererProvider) entry.provider(),
                entry.generation(), entry.descriptor()));
    }

    /** Resolves a hotswap renderer and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedHotswapRenderer> resolveHotswap(@Nullable String rawId) {
        return resolve(CommandHudSurface.HOTSWAP, rawId).map(entry -> new ResolvedHotswapRenderer(
                entry.registrationId(), (CommandHotswapHudRendererProvider) entry.provider(),
                entry.generation(), entry.descriptor()));
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<CommandTargetHudRendererProvider> findTargetRenderer(@Nullable String rawId) {
        return findTarget(rawId);
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<CommandHotswapHudRendererProvider> findHotswapRenderer(@Nullable String rawId) {
        return findHotswap(rawId);
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<ResolvedTargetRenderer> resolveTargetRenderer(@Nullable String rawId) {
        return resolveTarget(rawId);
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<ResolvedHotswapRenderer> resolveHotswapRenderer(@Nullable String rawId) {
        return resolveHotswap(rawId);
    }

    @Nonnull
    public synchronized Set<CommandHudRendererId> targetIds() { return registrations.ids(CommandHudSurface.TARGET); }

    @Nonnull
    public synchronized Set<CommandHudRendererId> hotswapIds() { return registrations.ids(CommandHudSurface.HOTSWAP); }

    /** Returns whether this registry still accepts registrations. */
    public boolean available() { return registrations.available(); }

    /** Returns whether one exact target renderer generation remains active. */
    public synchronized boolean isTargetActive(@Nullable CommandHudRendererId id, long generation) {
        return registrations.active(CommandHudSurface.TARGET, id, generation);
    }

    /** Returns whether one exact hotswap renderer generation remains active. */
    public synchronized boolean isHotswapActive(@Nullable CommandHudRendererId id, long generation) {
        return registrations.active(CommandHudSurface.HOTSWAP, id, generation);
    }

    /** Adds a listener for renderer-generation removal on either surface. */
    @Nonnull
    public AutoCloseable subscribeUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            return registrations.subscribeUnregister(required::unregistered);
        }
    }

    /** Adds a listener for one renderer surface. */
    @Nonnull
    public AutoCloseable subscribeSurfaceUnregister(@Nonnull SurfaceUnregisterListener listener) {
        SurfaceUnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            return registrations.subscribeSurfaceUnregister(required::unregistered);
        }
    }

    /** Adds a listener for target renderer-generation removal. */
    @Nonnull
    public AutoCloseable subscribeTargetUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        return subscribeSurfaceUnregister((surface, id, generation) -> {
            if (surface == CommandHudSurface.TARGET) required.unregistered(id, generation);
        });
    }

    /** Adds a listener for hotswap renderer-generation removal. */
    @Nonnull
    public AutoCloseable subscribeHotswapUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        return subscribeSurfaceUnregister((surface, id, generation) -> {
            if (surface == CommandHudSurface.HOTSWAP) required.unregistered(id, generation);
        });
    }

    /** Subscribes while one exact target generation remains active. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactTargetUnregister(
            @Nonnull CommandHudRendererId id, long generation, @Nonnull UnregisterListener listener
    ) { return subscribeExact(CommandHudSurface.TARGET, id, generation, listener); }

    /** Subscribes while one exact hotswap generation remains active. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactHotswapUnregister(
            @Nonnull CommandHudRendererId id, long generation, @Nonnull UnregisterListener listener
    ) { return subscribeExact(CommandHudSurface.HOTSWAP, id, generation, listener); }

    @Override
    public void close() { registrations.close(); }

    private CommandHudRegistrationResult register(
            CommandHudSurface surface, String rawId, CommandHudRendererDescriptor descriptor,
            Object provider, String missingProviderMessage
    ) {
        return registrations.register(surface, rawId, () -> CommandHudRendererId.tryParse(rawId), provider,
                descriptor, "Command HUD renderer ID must be a trimmed namespaced ID.",
                missingProviderMessage, "Command HUD renderer descriptor is required.");
    }

    private Optional<CommandHudRegistrationStore.Registration<CommandHudRendererId,
            CommandHudRendererDescriptor>> resolve(CommandHudSurface surface, String rawId) {
        return registrations.resolve(surface, () -> CommandHudRendererId.tryParse(rawId));
    }

    private ExactSubscription subscribeExact(
            CommandHudSurface surface, CommandHudRendererId id, long generation,
            UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        CommandHudRegistrationStore.ExactSubscription subscription =
                registrations.subscribeExact(surface, id, generation, listener::unregistered);
        return new ExactSubscription(subscription.active(), subscription.handle());
    }

    /** Internal target lookup result with exact registration identity. */
    public record ResolvedTargetRenderer(
            @Nonnull CommandHudRendererId id, @Nonnull CommandTargetHudRendererProvider provider,
            long generation, @Nonnull CommandHudRendererDescriptor descriptor
    ) {
        public ResolvedTargetRenderer {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Internal hotswap lookup result with exact registration identity. */
    public record ResolvedHotswapRenderer(
            @Nonnull CommandHudRendererId id, @Nonnull CommandHotswapHudRendererProvider provider,
            long generation, @Nonnull CommandHudRendererDescriptor descriptor
    ) {
        public ResolvedHotswapRenderer {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Atomic result for an exact-generation lifecycle subscription. */
    public record ExactSubscription(boolean active, @Nonnull AutoCloseable handle) {
        public ExactSubscription { java.util.Objects.requireNonNull(handle, "handle"); }
    }

    @FunctionalInterface
    public interface UnregisterListener { void unregistered(CommandHudRendererId id, long generation); }

    @FunctionalInterface
    public interface SurfaceUnregisterListener {
        void unregistered(CommandHudSurface surface, CommandHudRendererId id, long generation);
    }
}
