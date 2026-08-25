package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistration;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistrationResult;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe exact-generation registry for session-scoped contributors. */
public final class CommandUiContributorRegistry implements AutoCloseable {
    private final ConcurrentMap<CommandUiContributorId, Entry> contributors =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<UnregisterListener> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Nonnull
    public synchronized CommandUiRegistrationResult register(
            @Nullable String rawId,
            @Nullable CommandUiContributorProvider provider
    ) {
        if (closed.get()) return CommandUiRegistrationResult.unavailable(rawId);
        Optional<CommandUiContributorId> parsed = CommandUiContributorId.tryParse(rawId);
        if (parsed.isEmpty()) {
            return CommandUiRegistrationResult.invalid(rawId,
                    "Command UI contributor ID must be a trimmed namespaced ID.");
        }
        CommandUiContributorId id = parsed.orElseThrow();
        if (id.reserved()) {
            return CommandUiRegistrationResult.invalid(rawId,
                    "The tamework: contributor namespace is reserved.");
        }
        if (provider == null) {
            return CommandUiRegistrationResult.invalid(rawId,
                    "Command UI contributor provider is required.");
        }
        Entry entry = new Entry(id, provider, nextGeneration.incrementAndGet());
        if (contributors.putIfAbsent(id, entry) != null) {
            return CommandUiRegistrationResult.conflict(id.value());
        }
        return CommandUiRegistrationResult.registered(entry);
    }

    @Nonnull
    public synchronized Optional<CommandUiContributorProvider> find(
            @Nullable String rawId
    ) {
        Optional<CommandUiContributorId> parsed = CommandUiContributorId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        Entry entry = contributors.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty() : Optional.of(entry.provider);
    }

    @Nonnull
    public synchronized Optional<ResolvedContributor> resolve(
            @Nullable String rawId
    ) {
        Optional<CommandUiContributorId> parsed = CommandUiContributorId.tryParse(rawId);
        if (closed.get() || parsed.isEmpty()) return Optional.empty();
        Entry entry = contributors.get(parsed.orElseThrow());
        return entry == null || !entry.active()
                ? Optional.empty()
                : Optional.of(new ResolvedContributor(
                        entry.id, entry.provider, entry.generation));
    }

    @Nonnull
    public synchronized Set<CommandUiContributorId> listIds() {
        return closed.get() ? Set.of() : Set.copyOf(contributors.keySet());
    }

    /** Returns whether this registry still accepts registrations. */
    public boolean available() {
        return !closed.get();
    }

    /** Adds a listener for contributor-generation removal. */
    @Nonnull
    public AutoCloseable subscribeUnregister(
            @Nonnull UnregisterListener listener
    ) {
        UnregisterListener required = java.util.Objects.requireNonNull(
                listener, "listener");
        if (closed.get()) return () -> { };
        listeners.add(required);
        return () -> listeners.remove(required);
    }

    /** Returns whether one exact contributor generation remains registered. */
    public synchronized boolean isActive(
            @Nonnull CommandUiContributorId id,
            long generation
    ) {
        if (closed.get() || generation <= 0L || id == null) return false;
        Entry entry = contributors.get(id);
        return entry != null && entry.generation == generation && entry.active();
    }

    @Nonnull
    public synchronized ExactSubscription subscribeExactUnregister(
            @Nonnull CommandUiContributorId id,
            long generation,
            @Nonnull UnregisterListener listener
    ) {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(listener, "listener");
        Entry current = contributors.get(id);
        if (closed.get() || current == null || !current.active()
                || current.generation != generation) {
            return new ExactSubscription(false, () -> { });
        }
        ExactUnregisterListener exact = new ExactUnregisterListener(
                id, generation, listener);
        listeners.add(exact);
        return new ExactSubscription(true, exact);
    }

    @Override
    public void close() {
        java.util.List<Entry> removed;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            removed = java.util.List.copyOf(contributors.values());
            for (Entry entry : removed) entry.closed.set(true);
            contributors.clear();
        }
        for (Entry entry : removed) {
            notifyUnregister(entry.id, entry.generation);
        }
        listeners.clear();
    }

    private void notifyUnregister(CommandUiContributorId id, long generation) {
        for (UnregisterListener listener : listeners) {
            try {
                listener.unregistered(id, generation);
            } catch (RuntimeException | LinkageError ignored) {
                // One lifecycle listener must not block registry cleanup.
            }
        }
    }

    public record ResolvedContributor(
            @Nonnull CommandUiContributorId id,
            @Nonnull CommandUiContributorProvider provider,
            long generation
    ) {
    }

    public record ExactSubscription(boolean active, @Nonnull AutoCloseable handle) {
        public ExactSubscription {
            java.util.Objects.requireNonNull(handle, "handle");
        }
    }

    @FunctionalInterface
    public interface UnregisterListener {
        void unregistered(CommandUiContributorId id, long generation);
    }

    private final class ExactUnregisterListener
            implements UnregisterListener, AutoCloseable {
        private final CommandUiContributorId id;
        private final long generation;
        private final UnregisterListener delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        private ExactUnregisterListener(CommandUiContributorId id,
                                         long generation,
                                         UnregisterListener delegate) {
            this.id = id;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void unregistered(CommandUiContributorId removedId,
                                 long removedGeneration) {
            if (!id.equals(removedId) || generation != removedGeneration
                    || !ended.compareAndSet(false, true)) return;
            listeners.remove(this);
            delegate.unregistered(removedId, removedGeneration);
        }

        @Override
        public void close() {
            if (ended.compareAndSet(false, true)) listeners.remove(this);
        }
    }

    private final class Entry implements CommandUiRegistration {
        private final CommandUiContributorId id;
        private final CommandUiContributorProvider provider;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Entry(CommandUiContributorId id,
                       CommandUiContributorProvider provider,
                       long generation) {
            this.id = id;
            this.provider = provider;
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
            return !closed.get() && !CommandUiContributorRegistry.this.closed.get()
                    && contributors.get(id) == this;
        }

        @Override
        public void close() {
            boolean removed;
            synchronized (CommandUiContributorRegistry.this) {
                if (!closed.compareAndSet(false, true)) return;
                removed = contributors.remove(id, this);
            }
            if (removed) notifyUnregister(id, generation);
        }
    }
}
