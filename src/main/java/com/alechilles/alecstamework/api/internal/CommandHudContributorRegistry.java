package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudContributorProvider;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe exact-generation registry for target and hotswap HUD contributors. */
public final class CommandHudContributorRegistry implements AutoCloseable {
    private final CommandHudRegistrationStore<CommandHudContributorId, CommandHudContributorDescriptor>
            registrations = new CommandHudRegistrationStore<>(CommandHudContributorId::value);

    /** Registers a target contributor with an unrestricted descriptor. */
    /** Registers a target contributor and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId, @Nullable CommandTargetHudContributorProvider provider
    ) { return registerTarget(rawId, CommandHudContributorDescriptor.unrestricted(), provider); }

    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId, @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return register(CommandHudSurface.TARGET, rawId, descriptor, provider,
                "Command HUD target contributor provider is required.");
    }

    /** Registers a hotswap contributor with an unrestricted descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId, @Nullable CommandHotswapHudContributorProvider provider
    ) { return registerHotswap(rawId, CommandHudContributorDescriptor.unrestricted(), provider); }

    /** Registers a hotswap contributor and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId, @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return register(CommandHudSurface.HOTSWAP, rawId, descriptor, provider,
                "Command HUD hotswap contributor provider is required.");
    }

    /** Finds the currently active target contributor provider. */
    @Nonnull
    public synchronized Optional<CommandTargetHudContributorProvider> findTarget(@Nullable String rawId) {
        return resolve(CommandHudSurface.TARGET, rawId)
                .map(entry -> (CommandTargetHudContributorProvider) entry.provider());
    }

    /** Finds the currently active hotswap contributor provider. */
    @Nonnull
    public synchronized Optional<CommandHotswapHudContributorProvider> findHotswap(@Nullable String rawId) {
        return resolve(CommandHudSurface.HOTSWAP, rawId)
                .map(entry -> (CommandHotswapHudContributorProvider) entry.provider());
    }

    /** Resolves a target contributor and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedTargetContributor> resolveTarget(@Nullable String rawId) {
        return resolve(CommandHudSurface.TARGET, rawId).map(entry -> new ResolvedTargetContributor(
                entry.registrationId(), (CommandTargetHudContributorProvider) entry.provider(),
                entry.generation(), entry.descriptor()));
    }

    /** Resolves a hotswap contributor and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedHotswapContributor> resolveHotswap(@Nullable String rawId) {
        return resolve(CommandHudSurface.HOTSWAP, rawId).map(entry -> new ResolvedHotswapContributor(
                entry.registrationId(), (CommandHotswapHudContributorProvider) entry.provider(),
                entry.generation(), entry.descriptor()));
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<CommandTargetHudContributorProvider> findTargetContributor(@Nullable String rawId) {
        return findTarget(rawId);
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<CommandHotswapHudContributorProvider> findHotswapContributor(@Nullable String rawId) {
        return findHotswap(rawId);
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<ResolvedTargetContributor> resolveTargetContributor(@Nullable String rawId) {
        return resolveTarget(rawId);
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<ResolvedHotswapContributor> resolveHotswapContributor(@Nullable String rawId) {
        return resolveHotswap(rawId);
    }

    @Nonnull
    public synchronized Set<CommandHudContributorId> targetIds() {
        return registrations.ids(CommandHudSurface.TARGET);
    }

    @Nonnull
    public synchronized Set<CommandHudContributorId> hotswapIds() {
        return registrations.ids(CommandHudSurface.HOTSWAP);
    }

    /** Returns whether this registry still accepts registrations. */
    public boolean available() { return registrations.available(); }

    /** Returns whether one exact target contributor generation remains active. */
    public synchronized boolean isTargetActive(@Nullable CommandHudContributorId id, long generation) {
        return registrations.active(CommandHudSurface.TARGET, id, generation);
    }

    /** Returns whether one exact hotswap contributor generation remains active. */
    public synchronized boolean isHotswapActive(@Nullable CommandHudContributorId id, long generation) {
        return registrations.active(CommandHudSurface.HOTSWAP, id, generation);
    }

    /** Adds a listener for contributor-generation removal on either surface. */
    @Nonnull
    public AutoCloseable subscribeUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            return registrations.subscribeUnregister(required::unregistered);
        }
    }

    /** Adds a listener for one contributor surface. */
    @Nonnull
    public AutoCloseable subscribeSurfaceUnregister(@Nonnull SurfaceUnregisterListener listener) {
        SurfaceUnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            return registrations.subscribeSurfaceUnregister(required::unregistered);
        }
    }

    /** Adds a listener for target contributor-generation removal. */
    @Nonnull
    public AutoCloseable subscribeTargetUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(listener, "listener");
        return subscribeSurfaceUnregister((surface, id, generation) -> {
            if (surface == CommandHudSurface.TARGET) required.unregistered(id, generation);
        });
    }

    /** Adds a listener for hotswap contributor-generation removal. */
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
            @Nonnull CommandHudContributorId id, long generation, @Nonnull UnregisterListener listener
    ) { return subscribeExact(CommandHudSurface.TARGET, id, generation, listener); }

    /** Subscribes while one exact hotswap generation remains active. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactHotswapUnregister(
            @Nonnull CommandHudContributorId id, long generation, @Nonnull UnregisterListener listener
    ) { return subscribeExact(CommandHudSurface.HOTSWAP, id, generation, listener); }

    @Override
    public void close() { registrations.close(); }

    private CommandHudRegistrationResult register(
            CommandHudSurface surface, String rawId, CommandHudContributorDescriptor descriptor,
            Object provider, String missingProviderMessage
    ) {
        return registrations.register(surface, rawId, () -> CommandHudContributorId.tryParse(rawId),
                provider, descriptor, "Command HUD contributor ID must be a trimmed namespaced ID.",
                missingProviderMessage, "Command HUD contributor descriptor is required.");
    }

    private Optional<CommandHudRegistrationStore.Registration<CommandHudContributorId,
            CommandHudContributorDescriptor>> resolve(CommandHudSurface surface, String rawId) {
        return registrations.resolve(surface, () -> CommandHudContributorId.tryParse(rawId));
    }

    private ExactSubscription subscribeExact(
            CommandHudSurface surface, CommandHudContributorId id, long generation,
            UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        CommandHudRegistrationStore.ExactSubscription subscription =
                registrations.subscribeExact(surface, id, generation, listener::unregistered);
        return new ExactSubscription(subscription.active(), subscription.handle());
    }

    /** Internal target lookup result with exact registration identity. */
    public record ResolvedTargetContributor(
            @Nonnull CommandHudContributorId id, @Nonnull CommandTargetHudContributorProvider provider,
            long generation, @Nonnull CommandHudContributorDescriptor descriptor
    ) {
        public ResolvedTargetContributor {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Internal hotswap lookup result with exact registration identity. */
    public record ResolvedHotswapContributor(
            @Nonnull CommandHudContributorId id, @Nonnull CommandHotswapHudContributorProvider provider,
            long generation, @Nonnull CommandHudContributorDescriptor descriptor
    ) {
        public ResolvedHotswapContributor {
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
    public interface UnregisterListener { void unregistered(CommandHudContributorId id, long generation); }

    @FunctionalInterface
    public interface SurfaceUnregisterListener {
        void unregistered(CommandHudSurface surface, CommandHudContributorId id, long generation);
    }
}
