package com.alechilles.alecstamework.items;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Owns reference-counted chunk leases for queued companion relocations.
 *
 * <p>Each relocation must be opened before asynchronous chunk requests start and released when
 * that exact pending request terminalizes. Resources are tracked by identity so repeated loads of
 * the same chunk cannot increment the engine keep-loaded counter more than once.</p>
 */
final class CommandRelocationChunkLeaseService<K, T> implements AutoCloseable {
    private final ConcurrentHashMap<K, LeaseScope<T>> scopes = new ConcurrentHashMap<>();
    private final Consumer<T> retainAction;
    private final Consumer<T> releaseAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    CommandRelocationChunkLeaseService(@Nonnull Consumer<T> retainAction,
                                       @Nonnull Consumer<T> releaseAction) {
        this.retainAction = Objects.requireNonNull(retainAction, "retainAction");
        this.releaseAction = Objects.requireNonNull(releaseAction, "releaseAction");
    }

    boolean open(@Nonnull K key) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return false;
        }
        LeaseScope<T> scope = scopes.computeIfAbsent(
                key, ignored -> new LeaseScope<>(retainAction, releaseAction));
        if (!closed.get()) {
            return true;
        }
        if (scopes.remove(key, scope)) {
            scope.close();
        }
        return false;
    }

    boolean retain(@Nonnull K key, @Nonnull T resource) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(resource, "resource");
        LeaseScope<T> scope = scopes.get(key);
        return scope != null && scope.retain(resource);
    }

    void release(@Nonnull K key) {
        Objects.requireNonNull(key, "key");
        LeaseScope<T> scope = scopes.remove(key);
        if (scope != null) {
            scope.close();
        }
    }

    int activeScopeCount() {
        return scopes.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Map.Entry<K, LeaseScope<T>> entry : scopes.entrySet()) {
            if (scopes.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
            }
        }
    }

    private static final class LeaseScope<T> {
        private final IdentityHashMap<T, Boolean> retained = new IdentityHashMap<>();
        private final Consumer<T> retainAction;
        private final Consumer<T> releaseAction;
        private boolean closed;

        private LeaseScope(Consumer<T> retainAction, Consumer<T> releaseAction) {
            this.retainAction = retainAction;
            this.releaseAction = releaseAction;
        }

        private synchronized boolean retain(T resource) {
            if (closed) {
                return false;
            }
            if (retained.containsKey(resource)) {
                return true;
            }
            try {
                retainAction.accept(resource);
                retained.put(resource, Boolean.TRUE);
                return true;
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (T resource : retained.keySet()) {
                try {
                    releaseAction.accept(resource);
                } catch (RuntimeException | LinkageError ignored) {
                    // One broken release must not strand the remaining chunk leases.
                }
            }
            retained.clear();
        }
    }
}
