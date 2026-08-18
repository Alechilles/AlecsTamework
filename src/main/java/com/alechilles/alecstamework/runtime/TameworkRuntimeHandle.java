package com.alechilles.alecstamework.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns resources installed by one immutable runtime registration pass.
 *
 * <p>Resources close in reverse registration order. This mirrors dependency
 * ownership and prevents a provider from being closed before its consumers.</p>
 */
public final class TameworkRuntimeHandle implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean closed;

    /** Creates an empty runtime handle. */
    public TameworkRuntimeHandle() {
    }

    /** Adds one installed resource to this handle. */
    void add(AutoCloseable resource) {
        if (closed) {
            closeResource(resource);
            throw new IllegalStateException("Runtime handle is already closed");
        }
        if (resource != null) {
            resources.add(resource);
        }
    }

    /** Returns the number of resources currently owned by this handle. */
    public int size() {
        return resources.size();
    }

    /** Closes all resources in reverse registration order. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Exception failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        resources.clear();
        if (failure != null) {
            throw new RuntimeException("Tamework runtime resource shutdown failed", failure);
        }
    }

    private static void closeResource(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            throw new RuntimeException("Runtime resource could not be closed", exception);
        }
    }
}
