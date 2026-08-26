package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudRendererProvider;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe exact-generation registry for target and hotswap HUD renderers. */
public final class CommandHudRendererRegistry implements AutoCloseable {
    private final ConcurrentMap<CommandHudRendererId, TargetEntry> targetRenderers =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<CommandHudRendererId, HotswapEntry> hotswapRenderers =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<UnregisterListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SurfaceUnregisterListener> surfaceListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Registers a target renderer with an unrestricted descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return registerTarget(rawId, CommandHudRendererDescriptor.unrestricted(), provider);
    }

    /** Registers a target renderer and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerTarget(
            @Nullable String rawId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        if (closed.get()) return CommandHudRegistrationResult.unavailable(rawId);
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rawId);
        if (parsed.isEmpty()) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD renderer ID must be a trimmed namespaced ID.");
        }
        if (provider == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD target renderer provider is required.");
        }
        if (descriptor == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD renderer descriptor is required.");
        }
        CommandHudRendererId id = parsed.orElseThrow();
        TargetEntry entry = new TargetEntry(id, provider, descriptor,
                nextGeneration.incrementAndGet());
        if (targetRenderers.putIfAbsent(id, entry) != null) {
            return CommandHudRegistrationResult.conflict(id.value());
        }
        return CommandHudRegistrationResult.registered(entry);
    }

    /** Registers a hotswap renderer with an unrestricted descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return registerHotswap(rawId, CommandHudRendererDescriptor.unrestricted(), provider);
    }

    /** Registers a hotswap renderer and retains its immutable generation descriptor. */
    @Nonnull
    public synchronized CommandHudRegistrationResult registerHotswap(
            @Nullable String rawId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        if (closed.get()) return CommandHudRegistrationResult.unavailable(rawId);
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rawId);
        if (parsed.isEmpty()) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD renderer ID must be a trimmed namespaced ID.");
        }
        if (provider == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD hotswap renderer provider is required.");
        }
        if (descriptor == null) {
            return CommandHudRegistrationResult.invalid(rawId,
                    "Command HUD renderer descriptor is required.");
        }
        CommandHudRendererId id = parsed.orElseThrow();
        HotswapEntry entry = new HotswapEntry(id, provider, descriptor,
                nextGeneration.incrementAndGet());
        if (hotswapRenderers.putIfAbsent(id, entry) != null) {
            return CommandHudRegistrationResult.conflict(id.value());
        }
        return CommandHudRegistrationResult.registered(entry);
    }

    /** Finds the currently active target renderer provider. */
    @Nonnull
    public synchronized Optional<CommandTargetHudRendererProvider> findTarget(
            @Nullable String rawId
    ) {
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        TargetEntry entry = targetRenderers.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty() : Optional.of(entry.provider);
    }

    /** Finds the currently active hotswap renderer provider. */
    @Nonnull
    public synchronized Optional<CommandHotswapHudRendererProvider> findHotswap(
            @Nullable String rawId
    ) {
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        HotswapEntry entry = hotswapRenderers.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty() : Optional.of(entry.provider);
    }

    /** Resolves a target renderer and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedTargetRenderer> resolveTarget(
            @Nullable String rawId
    ) {
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        TargetEntry entry = targetRenderers.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty()
                : Optional.of(new ResolvedTargetRenderer(
                        entry.id, entry.provider, entry.generation, entry.descriptor));
    }

    /** Resolves a hotswap renderer and its exact generation descriptor. */
    @Nonnull
    public synchronized Optional<ResolvedHotswapRenderer> resolveHotswap(
            @Nullable String rawId
    ) {
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        HotswapEntry entry = hotswapRenderers.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty()
                : Optional.of(new ResolvedHotswapRenderer(
                        entry.id, entry.provider, entry.generation, entry.descriptor));
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<CommandTargetHudRendererProvider> findTargetRenderer(
            @Nullable String rawId
    ) {
        return findTarget(rawId);
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<CommandHotswapHudRendererProvider> findHotswapRenderer(
            @Nullable String rawId
    ) {
        return findHotswap(rawId);
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<ResolvedTargetRenderer> resolveTargetRenderer(
            @Nullable String rawId
    ) {
        return resolveTarget(rawId);
    }

    /** Alias for callers that include the renderer suffix in the method name. */
    @Nonnull
    public Optional<ResolvedHotswapRenderer> resolveHotswapRenderer(
            @Nullable String rawId
    ) {
        return resolveHotswap(rawId);
    }

    @Nonnull
    public synchronized Set<CommandHudRendererId> targetIds() {
        return closed.get() ? Set.of() : Set.copyOf(targetRenderers.keySet());
    }

    @Nonnull
    public synchronized Set<CommandHudRendererId> hotswapIds() {
        return closed.get() ? Set.of() : Set.copyOf(hotswapRenderers.keySet());
    }

    /** Returns whether this registry still accepts registrations. */
    public boolean available() {
        return !closed.get();
    }

    /** Returns whether one exact target renderer generation remains active. */
    public synchronized boolean isTargetActive(
            @Nullable CommandHudRendererId id,
            long generation
    ) {
        if (closed.get() || id == null || generation <= 0L) return false;
        TargetEntry entry = targetRenderers.get(id);
        return entry != null && entry.generation == generation && entry.active();
    }

    /** Returns whether one exact hotswap renderer generation remains active. */
    public synchronized boolean isHotswapActive(
            @Nullable CommandHudRendererId id,
            long generation
    ) {
        if (closed.get() || id == null || generation <= 0L) return false;
        HotswapEntry entry = hotswapRenderers.get(id);
        return entry != null && entry.generation == generation && entry.active();
    }

    /** Adds a listener for renderer-generation removal on either surface. */
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

    /** Adds a listener for one renderer surface. */
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

    /** Adds a listener for target renderer-generation removal. */
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

    /** Adds a listener for hotswap renderer-generation removal. */
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
            @Nonnull CommandHudRendererId id,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        TargetEntry current = targetRenderers.get(id);
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
            @Nonnull CommandHudRendererId id,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        HotswapEntry current = hotswapRenderers.get(id);
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
            for (TargetEntry entry : targetRenderers.values()) {
                entry.closed.set(true);
                removed.add(new Removal(CommandHudSurface.TARGET,
                        entry.id, entry.generation));
            }
            for (HotswapEntry entry : hotswapRenderers.values()) {
                entry.closed.set(true);
                removed.add(new Removal(CommandHudSurface.HOTSWAP,
                        entry.id, entry.generation));
            }
            targetRenderers.clear();
            hotswapRenderers.clear();
        }
        for (Removal removal : removed) {
            notifyUnregister(removal.surface(), removal.id(), removal.generation());
        }
        listeners.clear();
        surfaceListeners.clear();
    }

    private void notifyUnregister(
            CommandHudSurface surface,
            CommandHudRendererId id,
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
    public record ResolvedTargetRenderer(
            @Nonnull CommandHudRendererId id,
            @Nonnull CommandTargetHudRendererProvider provider,
            long generation,
            @Nonnull CommandHudRendererDescriptor descriptor
    ) {
        public ResolvedTargetRenderer {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(provider, "provider");
            java.util.Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Internal hotswap lookup result with exact registration identity. */
    public record ResolvedHotswapRenderer(
            @Nonnull CommandHudRendererId id,
            @Nonnull CommandHotswapHudRendererProvider provider,
            long generation,
            @Nonnull CommandHudRendererDescriptor descriptor
    ) {
        public ResolvedHotswapRenderer {
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
        void unregistered(CommandHudRendererId id, long generation);
    }

    @FunctionalInterface
    public interface SurfaceUnregisterListener {
        void unregistered(CommandHudSurface surface,
                          CommandHudRendererId id,
                          long generation);
    }

    private final class ExactUnregisterListener implements UnregisterListener, AutoCloseable {
        private final CommandHudSurface surface;
        private final CommandHudRendererId id;
        private final long generation;
        private final UnregisterListener delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        private ExactUnregisterListener(
                CommandHudSurface surface,
                CommandHudRendererId id,
                long generation,
                UnregisterListener delegate
        ) {
            this.surface = surface;
            this.id = id;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void unregistered(CommandHudRendererId removedId,
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
                           CommandHudRendererId id,
                           long generation) {
    }

    private final class TargetEntry implements CommandHudRegistration {
        private final CommandHudRendererId id;
        private final CommandTargetHudRendererProvider provider;
        private final CommandHudRendererDescriptor descriptor;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TargetEntry(CommandHudRendererId id,
                            CommandTargetHudRendererProvider provider,
                            CommandHudRendererDescriptor descriptor,
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
            return !closed.get() && !CommandHudRendererRegistry.this.closed.get()
                    && targetRenderers.get(id) == this;
        }

        @Override
        public void close() {
            boolean removed;
            synchronized (CommandHudRendererRegistry.this) {
                if (!closed.compareAndSet(false, true)) return;
                removed = targetRenderers.remove(id, this);
            }
            if (removed) notifyUnregister(CommandHudSurface.TARGET, id, generation);
        }
    }

    private final class HotswapEntry implements CommandHudRegistration {
        private final CommandHudRendererId id;
        private final CommandHotswapHudRendererProvider provider;
        private final CommandHudRendererDescriptor descriptor;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HotswapEntry(CommandHudRendererId id,
                             CommandHotswapHudRendererProvider provider,
                             CommandHudRendererDescriptor descriptor,
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
            return !closed.get() && !CommandHudRendererRegistry.this.closed.get()
                    && hotswapRenderers.get(id) == this;
        }

        @Override
        public void close() {
            boolean removed;
            synchronized (CommandHudRendererRegistry.this) {
                if (!closed.compareAndSet(false, true)) return;
                removed = hotswapRenderers.remove(id, this);
            }
            if (removed) notifyUnregister(CommandHudSurface.HOTSWAP, id, generation);
        }
    }
}
