package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryOutcomeProvider;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe, synchronous registry for one optional husbandry outcome provider. */
public final class HusbandryOutcomeRegistry implements HusbandryOutcomeApi, AutoCloseable {
    private final AtomicReference<HusbandryOutcomeProvider> provider = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public boolean available() {
        return !closed.get();
    }

    @Override
    @Nonnull
    public AutoCloseable register(@Nonnull HusbandryOutcomeProvider candidate) {
        HusbandryOutcomeProvider checked = requireProvider(candidate);
        if (closed.get()) {
            throw new IllegalStateException("Husbandry outcome registry is closed");
        }
        if (!provider.compareAndSet(null, checked)) {
            throw new IllegalStateException("A husbandry outcome provider is already registered");
        }
        if (closed.get() && provider.compareAndSet(checked, null)) {
            throw new IllegalStateException("Husbandry outcome registry is closed");
        }
        return new RegistrationHandle(checked);
    }

    @Override
    @Nonnull
    public HusbandryOutcomeModifiers resolve(@Nonnull HusbandryOutcomeContext context) {
        if (context == null || closed.get()) {
            return HusbandryOutcomeModifiers.identity();
        }
        HusbandryOutcomeProvider active = provider.get();
        if (active == null) {
            return HusbandryOutcomeModifiers.identity();
        }
        try {
            HusbandryOutcomeModifiers modifiers = active.resolve(copyContext(context));
            return modifiers == null ? HusbandryOutcomeModifiers.identity() : normalize(modifiers);
        } catch (Throwable ignored) {
            return HusbandryOutcomeModifiers.identity();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            provider.set(null);
        }
    }

    @Nonnull
    private HusbandryOutcomeProvider requireProvider(@Nullable HusbandryOutcomeProvider candidate) {
        if (candidate == null) {
            throw new NullPointerException("provider");
        }
        return candidate;
    }

    @Nonnull
    private HusbandryOutcomeContext copyContext(@Nonnull HusbandryOutcomeContext context) {
        Set<String> groups = context.groupIds() == null
                ? Set.of()
                : Set.copyOf(context.groupIds());
        return new HusbandryOutcomeContext(
                context.kind(),
                context.ownerId(),
                context.companionId(),
                context.roleId(),
                context.profileId(),
                groups,
                context.productId()
        );
    }

    @Nonnull
    private HusbandryOutcomeModifiers normalize(@Nonnull HusbandryOutcomeModifiers modifiers) {
        HusbandryOutcomeModifiers identity = HusbandryOutcomeModifiers.identity();
        return new HusbandryOutcomeModifiers(
                clamp(modifiers.careRestorationMultiplier(), 1.0, 2.0,
                        identity.careRestorationMultiplier()),
                clamp(modifiers.productBonusChance(), 0.0, 1.0,
                        identity.productBonusChance()),
                clamp(modifiers.doubleBonusChance(), 0.0, 1.0,
                        identity.doubleBonusChance()),
                clamp(modifiers.breedingCooldownMultiplier(), 0.25, 1.0,
                        identity.breedingCooldownMultiplier())
        );
    }

    private double clamp(double value, double minimum, double maximum, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class RegistrationHandle implements AutoCloseable {
        private final HusbandryOutcomeProvider registered;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegistrationHandle(HusbandryOutcomeProvider registered) {
            this.registered = registered;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                provider.compareAndSet(registered, null);
            }
        }
    }
}
