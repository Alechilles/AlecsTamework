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
        }
        providers.clear();
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
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            providers.remove(providerId, this);
        }
    }
}
