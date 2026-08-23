package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import com.alechilles.alecstamework.api.commandui.CommandUiProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderRegistration;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderRegistrationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thread-safe registry for externally supplied command UI providers.
 *
 * <p>Provider replacement is explicit: duplicate live identifiers return a
 * conflict result. Registration handles remove only their exact generation,
 * which prevents an old plugin shutdown callback from removing a newer
 * provider.</p>
 */
public final class CommandUiProviderRegistry implements CommandUiApi, AutoCloseable {
    private final ConcurrentMap<CommandUiProviderId, Entry> providers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<UnregisterListener> unregisterListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public boolean available() {
        return !closed.get();
    }

    @Override
    @Nonnull
    public synchronized CommandUiProviderRegistrationResult register(
            @Nullable String rawProviderId,
            @Nullable CommandUiProvider provider
    ) {
        if (closed.get()) {
            return CommandUiProviderRegistrationResult.unavailable(rawProviderId);
        }
        Optional<CommandUiProviderId> parsed = CommandUiProviderId.tryParse(rawProviderId);
        if (parsed.isEmpty()) {
            return CommandUiProviderRegistrationResult.invalid(
                    rawProviderId,
                    "Command UI provider ID must be a trimmed namespaced ID."
            );
        }
        CommandUiProviderId providerId = parsed.orElseThrow();
        if (providerId.isReserved()) {
            return CommandUiProviderRegistrationResult.invalid(
                    rawProviderId,
                    "The tamework: provider namespace is reserved."
            );
        }
        if (provider == null) {
            return CommandUiProviderRegistrationResult.invalid(
                    rawProviderId,
                    "Command UI provider is required."
            );
        }

        Entry entry = new Entry(
                providerId,
                provider,
                nextGeneration.incrementAndGet()
        );
        if (providers.putIfAbsent(providerId, entry) != null) {
            return CommandUiProviderRegistrationResult.conflict(providerId);
        }
        return CommandUiProviderRegistrationResult.registered(providerId, entry);
    }

    @Override
    @Nonnull
    public synchronized Optional<CommandUiProvider> find(@Nullable String rawProviderId) {
        Optional<CommandUiProviderId> parsed = CommandUiProviderId.tryParse(rawProviderId);
        if (parsed.isEmpty() || closed.get()) {
            return Optional.empty();
        }
        Entry entry = providers.get(parsed.orElseThrow());
        return entry == null || entry.closed.get()
                ? Optional.empty()
                : Optional.of(entry.provider);
    }

    /** Resolves one active provider and its exact registration generation. */
    @Nonnull
    public synchronized Optional<ResolvedProvider> resolve(
            @Nullable String rawProviderId) {
        Optional<CommandUiProviderId> parsed = CommandUiProviderId.tryParse(rawProviderId);
        if (parsed.isEmpty() || closed.get()) return Optional.empty();
        Entry entry = providers.get(parsed.orElseThrow());
        if (entry == null || !entry.active()) return Optional.empty();
        return Optional.of(new ResolvedProvider(
                entry.providerId, entry.provider, entry.generation));
    }

    /** Adds an internal listener for exact provider-generation removal. */
    @Nonnull
    public AutoCloseable subscribeUnregister(
            @Nonnull UnregisterListener listener) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        if (closed.get()) return () -> { };
        unregisterListeners.add(required);
        return () -> unregisterListeners.remove(required);
    }

    /** Subscribes to one exact generation and reports an already-ended generation. */
    @Nonnull
    public synchronized ExactSubscription subscribeExactUnregister(
            @Nonnull CommandUiProviderId providerId,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        Entry current = providers.get(java.util.Objects.requireNonNull(
                providerId, "providerId"));
        if (closed.get() || current == null || !current.active()
                || current.generation != generation) {
            return new ExactSubscription(false, () -> { });
        }
        ExactUnregisterListener exact = new ExactUnregisterListener(
                providerId, generation, required);
        unregisterListeners.add(exact);
        return new ExactSubscription(true, exact);
    }

    @Override
    @Nonnull
    public synchronized Set<CommandUiProviderId> listProviderIds() {
        if (closed.get()) {
            return Set.of();
        }
        return Set.copyOf(providers.keySet());
    }

    /** Returns a stable, generation-ordered view for internal host diagnostics. */
    @Nonnull
    public synchronized List<CommandUiProviderId> providerIdsInRegistrationOrder() {
        if (closed.get()) {
            return List.of();
        }
        ArrayList<Entry> entries = new ArrayList<>(providers.values());
        entries.sort(Comparator.comparingLong(entry -> entry.generation));
        return entries.stream().map(entry -> entry.providerId).toList();
    }

    /** Closes the registry and removes every provider generation. */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Entry entry : providers.values()) {
            entry.closed.set(true);
            notifyUnregister(entry.providerId, entry.generation);
        }
        providers.clear();
        unregisterListeners.clear();
    }

    private void notifyUnregister(CommandUiProviderId providerId, long generation) {
        for (UnregisterListener listener : unregisterListeners) {
            try {
                listener.unregistered(providerId, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One host listener must not block provider removal.
            }
        }
    }

    /** Internal provider lookup result with exact registration identity. */
    public record ResolvedProvider(
            @Nonnull CommandUiProviderId providerId,
            @Nonnull CommandUiProvider provider,
            long generation
    ) {
    }

    /** Atomic result for exact provider-generation host subscription. */
    public record ExactSubscription(boolean active, AutoCloseable handle) {
        public ExactSubscription {
            java.util.Objects.requireNonNull(handle, "handle");
        }
    }

    /** Internal exact-generation removal callback. */
    @FunctionalInterface
    public interface UnregisterListener {
        void unregistered(CommandUiProviderId providerId, long generation);
    }

    private final class ExactUnregisterListener
            implements UnregisterListener, AutoCloseable {
        private final CommandUiProviderId providerId;
        private final long generation;
        private final UnregisterListener delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ExactUnregisterListener(
                CommandUiProviderId providerId,
                long generation,
                UnregisterListener delegate
        ) {
            this.providerId = providerId;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void unregistered(CommandUiProviderId removedProviderId,
                                 long removedGeneration) {
            if (!providerId.equals(removedProviderId)
                    || generation != removedGeneration
                    || !closed.compareAndSet(false, true)) return;
            unregisterListeners.remove(this);
            delegate.unregistered(removedProviderId, removedGeneration);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            unregisterListeners.remove(this);
        }
    }

    private final class Entry implements CommandUiProviderRegistration {
        private final CommandUiProviderId providerId;
        private final CommandUiProvider provider;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Entry(
                @Nonnull CommandUiProviderId providerId,
                @Nonnull CommandUiProvider provider,
                long generation
        ) {
            this.providerId = providerId;
            this.provider = provider;
            this.generation = generation;
        }

        @Override
        @Nonnull
        public CommandUiProviderId providerId() {
            return providerId;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public boolean active() {
            return !closed.get()
                    && !CommandUiProviderRegistry.this.closed.get()
                    && providers.get(providerId) == this;
        }

        @Override
        public void close() {
            synchronized (CommandUiProviderRegistry.this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                if (providers.remove(providerId, this)) {
                    notifyUnregister(providerId, generation);
                }
            }
        }
    }
}
