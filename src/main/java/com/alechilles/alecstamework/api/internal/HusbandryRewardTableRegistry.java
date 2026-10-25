package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryRewardTable;
import com.alechilles.alecstamework.api.HusbandryRewardTableApi;
import com.alechilles.alecstamework.api.HusbandryRewardTableProvider;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe, synchronous registry for one optional husbandry reward table provider. */
public final class HusbandryRewardTableRegistry implements HusbandryRewardTableApi, AutoCloseable {
    private final AtomicReference<HusbandryRewardTableProvider> provider = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    @Override
    public boolean available() {
        return !closed.get();
    }

    @Override
    @Nonnull
    public AutoCloseable register(@Nonnull HusbandryRewardTableProvider candidate) {
        if (candidate == null) {
            throw new NullPointerException("provider");
        }
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Husbandry reward table registry is closed");
            }
            if (!provider.compareAndSet(null, candidate)) {
                throw new IllegalStateException("A husbandry reward table provider is already registered");
            }
            return new RegistrationHandle(candidate);
        }
    }

    /** Returns the provider's table; no provider, a null answer or a throw all keep the configured list. */
    @Override
    @Nullable
    public HusbandryRewardTable resolve(@Nonnull HusbandryOutcomeContext context) {
        if (context == null || closed.get()) {
            return null;
        }
        HusbandryRewardTableProvider active = provider.get();
        if (active == null) {
            return null;
        }
        try {
            // The table's constructor already dropped nulls and bounded its size and values.
            return active.resolve(copyContext(context));
        } catch (Throwable ignored) {
            return null;
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
    private static HusbandryOutcomeContext copyContext(@Nonnull HusbandryOutcomeContext context) {
        Set<String> groups = context.groupIds() == null ? Set.of() : Set.copyOf(context.groupIds());
        return new HusbandryOutcomeContext(
                context.kind(),
                context.ownerId(),
                context.actorId(),
                context.companionId(),
                context.roleId(),
                context.profileId(),
                groups,
                context.productId(),
                context.tool()
        );
    }

    private final class RegistrationHandle implements AutoCloseable {
        private final HusbandryRewardTableProvider registered;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegistrationHandle(HusbandryRewardTableProvider registered) {
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
