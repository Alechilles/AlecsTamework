package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudContributorProvider;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe exact-generation registry for target and hotswap HUD contributors. */
public final class CommandHudContributorRegistry implements AutoCloseable {
    private final ConcurrentMap<CommandHudContributorId, TargetEntry> targetContributors =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CommandHudContributorId, HotswapEntry> hotswapContributors =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<UnregisterListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SurfaceUnregisterListener> surfaceListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Registers a target contributor with an unrestricted descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return registerTarget(rawId, CommandHudContributorDescriptor.unrestricted(), provider);
    }

    /** Registers a target contributor and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        if (closed.get()) return CommandHudRegistrationResult.unavailable(rawId);
        Optional<CommandHudContributorId> parsed = CommandHudContributorId.tryParse(rawId);
        if (parsed.isEmpty()) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD contributor ID must be a trimmed namespaced ID.");
        }
        if (provider == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD target contributor provider is required.");
        }
        if (descriptor == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD contributor descriptor is required.");
        }
        CommandHudContributorId id = parsed.orElseThrow();
        TargetEntry entry = new TargetEntry(id, provider, descriptor,
                nextGeneration.incrementAndGet());
        if (targetContributors.putIfAbsent(id, entry) != null) {
            return CommandHudRegistrationResult.conflict(id.value());
        }
        return CommandHudRegistrationResult.registered(entry);
    }

    /** Registers a hotswap contributor with an unrestricted descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return registerHotswap(rawId, CommandHudContributorDescriptor.unrestricted(), provider);
    }

    /** Registers a hotswap contributor and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        if (closed.get()) return CommandHudRegistrationResult.unavailable(rawId);
        Optional<CommandHudContributorId> parsed = CommandHudContributorId.tryParse(rawId);
        if (parsed.isEmpty()) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD contributor ID must be a trimmed namespaced ID.");
        }
        if (provider == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD hotswap contributor provider is required.");
        }
        if (descriptor == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD contributor descriptor is required.");
        }
        CommandHudContributorId id = parsed.orElseThrow();
        HotswapEntry entry = new HotswapEntry(id, provider, descriptor,
                nextGeneration.incrementAndGet());
        if (hotswapContributors.putIfAbsent(id, entry) != null) {
            return CommandHudRegistrationResult.conflict(id.value());
        }
        return CommandHudRegistrationResult.registered(entry);
    }

    /** Finds the currently active target contributor provider. */
    @Nonnull
    public synchronized Optional<CommandTargetHudContributorProvider> findTarget(
            @Nullable String rawId
    ) {
        Optional<CommandHudContributorId> parsed = CommandHudContributorId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        TargetEntry entry = targetContributors.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty() : Optional.of(entry.provider);
    }

    /** Finds the currently active hotswap contributor provider. */
    @Nonnull
    public synchronized Optional<CommandHotswapHudContributorProvider> findHotswap(
            @Nullable String rawId
    ) {
        Optional<CommandHudContributorId> parsed = CommandHudContributorId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        HotswapEntry entry = hotswapContributors.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty() : Optional.of(entry.provider);
    }

    /** Resolves a target contributor and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedTargetContributor> resolveTarget(
            @Nullable String rawId
    ) {
        Optional<CommandHudContributorId> parsed = CommandHudContributorId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        TargetEntry entry = targetContributors.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty()
                : Optional.of(new ResolvedTargetContributor(
                        entry.id, entry.provider, entry.generation, entry.descriptor));
    }

    /** Resolves a hotswap contributor and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedHotswapContributor> resolveHotswap(
            @Nullable String rawId
    ) {
        Optional<CommandHudContributorId> parsed = CommandHudContributorId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        HotswapEntry entry = hotswapContributors.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty()
                : Optional.of(new ResolvedHotswapContributor(
                        entry.id, entry.provider, entry.generation, entry.descriptor));
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<CommandTargetHudContributorProvider> findTargetContributor(
            @Nullable String rawId
    ) {
        return findTarget(rawId);
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<CommandHotswapHudContributorProvider> findHotswapContributor(
            @Nullable String rawId
    ) {
        return findHotswap(rawId);
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<ResolvedTargetContributor> resolveTargetContributor(
            @Nullable String rawId
    ) {
        return resolveTarget(rawId);
    }

    /** Alias for callers that include the contributor suffix in the method name. */
    @Nonnull
    public Optional<ResolvedHotswapContributor> resolveHotswapContributor(
            @Nullable String rawId
    ) {
        return resolveHotswap(rawId);
    }

    @Nonnull
    public synchronized Set<CommandHudContributorId> targetIds() {
        return closed.get() ? Set.of() : Set.copyOf(targetContributors.keySet());
    }

    @Nonnull
    public synchronized Set<CommandHudContributorId> hotswapIds() {
        return closed.get() ? Set.of() : Set.copyOf(hotswapContributors.keySet());
    }

    /** Returns whether this registry still accepts registrations. */
    public boolean available() {
        return !closed.get();
    }

    /** Returns whether one exact target contributor generation remains active. */
    public synchronized boolean isTargetActive(
            @Nullable CommandHudContributorId id,
            long generation
    ) {
        if (closed.get() || id == null || generation <= 0L) return false;
        TargetEntry entry = targetContributors.get(id);
        return entry != null && entry.generation == generation && entry.active();
    }

    /** Returns whether one exact hotswap contributor generation remains active. */
    public synchronized boolean isHotswapActive(
            @Nullable CommandHudContributorId id,
            long generation
    ) {
        if (closed.get() || id == null || generation <= 0L) return false;
        HotswapEntry entry = hotswapContributors.get(id);
        return entry != null && entry.generation == generation && entry.active();
    }

    /** Adds a listener for contributor-generation removal on either surface. */
    @Nonnull
    public AutoCloseable subscribeUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        synchronized (this) {
            if (closed.get()) return () -> { };
            listeners.add(required);
        }
        return () -> listeners.remove(required);
    }

    /** Adds a listener for one contributor surface. */
    @Nonnull
    public AutoCloseable subscribeSurfaceUnregister(
            @Nonnull SurfaceUnregisterListener listener
    ) {
        SurfaceUnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        synchronized (this) {
            if (closed.get()) return () -> { };
            surfaceListeners.add(required);
        }
        return () -> surfaceListeners.remove(required);
    }

    /** Adds a listener for target contributor-generation removal. */
    @Nonnull
    public AutoCloseable subscribeTargetUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        return subscribeSurfaceUnregister((surface, id, generation) -> {
            if (surface == CommandHudSurface.TARGET) {
                required.unregistered(id, generation);
            }
        });
    }

    /** Adds a listener for hotswap contributor-generation removal. */
    @Nonnull
    public AutoCloseable subscribeHotswapUnregister(@Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        return subscribeSurfaceUnregister((surface, id, generation) -> {
            if (surface == CommandHudSurface.HOTSWAP) {
                required.unregistered(id, generation);
            }
        });
    }

    /** Subscribes while one exact target generation remains active. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactTargetUnregister(
            @Nonnull CommandHudContributorId id,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        TargetEntry current = targetContributors.get(id);
        if (closed.get() || current == null || !current.active()
                || current.generation != generation) {
            return new ExactSubscription(false, () -> { });
        }
        ExactUnregisterListener exact = new ExactUnregisterListener(
                CommandHudSurface.TARGET, id, generation, listener);
        listeners.add(exact);
        return new ExactSubscription(true, exact);
    }

    /** Subscribes while one exact hotswap generation remains active. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactHotswapUnregister(
            @Nonnull CommandHudContributorId id,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        HotswapEntry current = hotswapContributors.get(id);
        if (closed.get() || current == null || !current.active()
                || current.generation != generation) {
            return new ExactSubscription(false, () -> { });
        }
        ExactUnregisterListener exact = new ExactUnregisterListener(
                CommandHudSurface.HOTSWAP, id, generation, listener);
        listeners.add(exact);
        return new ExactSubscription(true, exact);
    }

    @Override
    public void close() {
        java.util.List<Removal> removed = new java.util.ArrayList<>();
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            for (TargetEntry entry : targetContributors.values()) {
                entry.closed.set(true);
                removed.add(new Removal(CommandHudSurface.TARGET,
                        entry.id, entry.generation));
            }
            for (HotswapEntry entry : hotswapContributors.values()) {
                entry.closed.set(true);
                removed.add(new Removal(CommandHudSurface.HOTSWAP,
                        entry.id, entry.generation));
            }
            targetContributors.clear();
            hotswapContributors.clear();
        }
        for (Removal removal : removed) {
            notifyUnregister(removal.surface(), removal.id(), removal.generation());
        }
        listeners.clear();
        surfaceListeners.clear();
    }

    private void notifyUnregister(
            CommandHudSurface surface,
            CommandHudContributorId id,
            long generation
    ) {
        for (UnregisterListener listener : listeners) {
            try {
                listener.unregistered(id, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One lifecycle listener must not block registry cleanup.
            }
        }
        for (SurfaceUnregisterListener listener : surfaceListeners) {
            try {
                listener.unregistered(surface, id, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One lifecycle listener must not block registry cleanup.
            }
        }
    }

    /** Internal target lookup result with exact registration identity. */
    public record ResolvedTargetContributor(
            @Nonnull CommandHudContributorId id,
            @Nonnull CommandTargetHudContributorProvider provider,
            long generation,
            @Nonnull CommandHudContributorDescriptor descriptor
    ) {
        public ResolvedTargetContributor {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Internal hotswap lookup result with exact registration identity. */
    public record ResolvedHotswapContributor(
            @Nonnull CommandHudContributorId id,
            @Nonnull CommandHotswapHudContributorProvider provider,
            long generation,
            @Nonnull CommandHudContributorDescriptor descriptor
    ) {
        public ResolvedHotswapContributor {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Atomic result for an exact-generation lifecycle subscription. */
    public record ExactSubscription(boolean active, @Nonnull AutoCloseable handle) {
        public ExactSubscription {
            java.util.Objects.requireNonNull(handle, "handle");
        }
    }

    @FunctionalInterface
    public interface UnregisterListener {
        void unregistered(CommandHudContributorId id, long generation);
    }

    @FunctionalInterface
    public interface SurfaceUnregisterListener {
        void unregistered(CommandHudSurface surface,
                          CommandHudContributorId id,
                          long generation);
    }

    private final class ExactUnregisterListener implements UnregisterListener, AutoCloseable {
        private final CommandHudSurface surface;
        private final CommandHudContributorId id;
        private final long generation;
        private final UnregisterListener delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        private ExactUnregisterListener(
                CommandHudSurface surface,
                CommandHudContributorId id,
                long generation,
                UnregisterListener delegate
        ) {
            this.surface = surface;
            this.id = id;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void unregistered(CommandHudContributorId removedId,
                                 long removedGeneration) {
            if (!id.equals(removedId) || generation != removedGeneration
                    || !ended.compareAndSet(false, true)) return;
            listeners.remove(this);
            delegate.unregistered(removedId, removedGeneration);
        }

        @SuppressWarnings("unused")
        private boolean matches(CommandHudSurface removedSurface) {
            return surface == removedSurface;
        }

        @Override
        public void close() {
            if (ended.compareAndSet(false, true)) listeners.remove(this);
        }
    }

    private record Removal(CommandHudSurface surface,
                           CommandHudContributorId id,
                           long generation) {
    }

    private final class TargetEntry implements CommandHudRegistration {
        private final CommandHudContributorId id;
        private final CommandTargetHudContributorProvider provider;
        private final CommandHudContributorDescriptor descriptor;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TargetEntry(CommandHudContributorId id,
                            CommandTargetHudContributorProvider provider,
                            CommandHudContributorDescriptor descriptor,
                            long generation) {
            this.id = id;
            this.provider = provider;
            this.descriptor = descriptor;
            this.generation = generation;
        }

        @Override
        @Nonnull
        public String id() {
            return id.value();
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public boolean active() {
            return !closed.get() && !CommandHudContributorRegistry.this.closed.get()
                    && targetContributors.get(id) == this;
        }

        @Override
        public void close() {
            boolean removed;
            synchronized (CommandHudContributorRegistry.this) {
                if (!closed.compareAndSet(false, true)) return;
                removed = targetContributors.remove(id, this);
            }
            if (removed) notifyUnregister(CommandHudSurface.TARGET, id, generation);
        }
    }

    private final class HotswapEntry implements CommandHudRegistration {
        private final CommandHudContributorId id;
        private final CommandHotswapHudContributorProvider provider;
        private final CommandHudContributorDescriptor descriptor;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HotswapEntry(CommandHudContributorId id,
                             CommandHotswapHudContributorProvider provider,
                             CommandHudContributorDescriptor descriptor,
                             long generation) {
            this.id = id;
            this.provider = provider;
            this.descriptor = descriptor;
            this.generation = generation;
        }

        @Override
        @Nonnull
        public String id() {
            return id.value();
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public boolean active() {
            return !closed.get() && !CommandHudContributorRegistry.this.closed.get()
                    && hotswapContributors.get(id) == this;
        }

        @Override
        public void close() {
            boolean removed;
            synchronized (CommandHudContributorRegistry.this) {
                if (!closed.compareAndSet(false, true)) return;
                removed = hotswapContributors.remove(id, this);
            }
            if (removed) notifyUnregister(CommandHudSurface.HOTSWAP, id, generation);
        }
    }
}
