package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignal;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignalSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Read-only, bounded bridge from async database snapshots to command cards.
 * Only requested offline profiles are read, at most 16 concurrently. Entries expire after
 * a minute or a profile change; failed reads retry after ten seconds. No worker or timer
 * is owned here. Completion signals use the page's existing world-thread refresh dispatcher.
 */
final class CommandSavedNpcPanelCache implements AutoCloseable {
    private final Function<ProfileId, CompletionStage<CommandSavedNpcPanelSnapshot>> reads;
    private final LongSupplier clock;
    private final Map<ProfileId, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final CopyOnWriteArrayList<Subscription> listeners = new CopyOnWriteArrayList<>();
    private boolean closed;

    CommandSavedNpcPanelCache(Function<ProfileId, CompletionStage<CommandSavedNpcPanelSnapshot>> reads) {
        this(reads, System::nanoTime);
    }

    CommandSavedNpcPanelCache(Function<ProfileId, CompletionStage<CommandSavedNpcPanelSnapshot>> reads,
                             LongSupplier clock) {
        this.reads = reads;
        this.clock = clock;
    }

    synchronized CommandSavedNpcPanelSnapshot peek(ProfileId id, UUID owner, Revision revision) {
        if (closed || id == null) return null;
        long now = clock.getAsLong();
        Entry entry = entries.get(id);
        if (entry != null && entry.future.isDone()) {
            long lifetime = entry.future.getNow(null) == null ? 10_000_000_000L : 60_000_000_000L;
            if (!entry.revision.equals(revision) || now - entry.started >= lifetime) {
                entries.remove(id);
                entry = null;
            }
        }
        if (entry == null) {
            if (entries.values().stream().filter(e -> !e.future.isDone()).count() >= 16) return null;
            if (entries.size() >= 256) {
                var iterator = entries.values().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().future.isDone()) { iterator.remove(); break; }
                }
            }
            entry = new Entry(revision, now, new CompletableFuture<>(), java.util.concurrent.ConcurrentHashMap.newKeySet());
            entries.put(id, entry);
            Entry requested = entry;
            if (owner != null) requested.viewers.add(owner);
            try {
                reads.apply(id).whenComplete((result, error) -> {
                    requested.future.complete(error == null ? result : null);
                    for (Subscription subscription : listeners) {
                        if (requested.viewers.contains(subscription.owner)) {
                            try { subscription.signal.run(); } catch (RuntimeException ignored) { }
                        }
                    }
                });
            } catch (RuntimeException failure) {
                requested.future.complete(null);
            }
        }
        if (owner != null) entry.viewers.add(owner);
        return entry.future.getNow(null);
    }

    LinkedPanelRefreshSignalSource signals(UUID owner) {
        return listener -> {
            Subscription subscription = new Subscription(owner, () -> listener.accept(
                    new LinkedPanelRefreshSignal(LinkedPanelRefreshSignal.Kind.IMMEDIATE)));
            synchronized (this) {
                if (closed) return () -> { };
                listeners.add(subscription);
            }
            return () -> listeners.remove(subscription);
        };
    }

    @Override public synchronized void close() { closed = true; entries.clear(); listeners.clear(); }
    record Revision(long profileUpdatedAtMs, String checkpointKey, long checkpointRevision) { }
    private record Entry(Revision revision, long started, CompletableFuture<CommandSavedNpcPanelSnapshot> future,
                         java.util.Set<UUID> viewers) { }
    private record Subscription(UUID owner, Runnable signal) { }
}
