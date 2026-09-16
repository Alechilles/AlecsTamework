package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CapturedItemDisplayApi;
import com.alechilles.alecstamework.api.CapturedItemDisplayContext;
import com.alechilles.alecstamework.api.CapturedItemDisplayContribution;
import com.alechilles.alecstamework.api.CapturedItemDisplayProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe, synchronous registry for one optional captured-item display provider. */
public final class CapturedItemDisplayRegistry implements CapturedItemDisplayApi, AutoCloseable {
    private final AtomicReference<CapturedItemDisplayProvider> provider = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    @Override
    public boolean available() {
        return !closed.get();
    }

    @Override
    @Nonnull
    public AutoCloseable register(@Nonnull CapturedItemDisplayProvider candidate) {
        CapturedItemDisplayProvider checked = requireProvider(candidate);
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Captured item display registry is closed");
            }
            if (!provider.compareAndSet(null, checked)) {
                throw new IllegalStateException("A captured item display provider is already registered");
            }
            return new RegistrationHandle(checked);
        }
    }

    @Override
    @Nonnull
    public CapturedItemDisplayContribution resolve(@Nonnull CapturedItemDisplayContext context) {
        if (context == null || closed.get()) {
            return CapturedItemDisplayContribution.none();
        }
        CapturedItemDisplayProvider active = provider.get();
        if (active == null) {
            return CapturedItemDisplayContribution.none();
        }
        try {
            CapturedItemDisplayContribution contribution = active.resolve(context);
            return contribution == null ? CapturedItemDisplayContribution.none() : contribution;
        } catch (RuntimeException | LinkageError ignored) {
            return CapturedItemDisplayContribution.none();
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed.compareAndSet(false, true)) {
                provider.set(null);
            }
        }
    }

    @Nonnull
    private CapturedItemDisplayProvider requireProvider(@Nullable CapturedItemDisplayProvider candidate) {
        if (candidate == null) {
            throw new NullPointerException("provider");
        }
        return candidate;
    }

    private final class RegistrationHandle implements AutoCloseable {
        private final CapturedItemDisplayProvider registered;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegistrationHandle(CapturedItemDisplayProvider registered) {
            this.registered = registered;
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed.compareAndSet(false, true)) {
                    provider.compareAndSet(registered, null);
                }
            }
        }
    }
}
