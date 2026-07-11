package com.alechilles.alecstamework.items;

import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Asset-interaction bridge installed and cleared by the plugin lifecycle. */
public final class ManagedCoopItemIntakeRuntime {
    private static final AtomicReference<Services> SERVICES =
            new AtomicReference<>();

    private ManagedCoopItemIntakeRuntime() {
    }

    /** Installs the single persistence-backed handler used by decoded interaction assets. */
    public static void install(@Nonnull ManagedCoopItemIntakeHandler handler,
                               @Nonnull ManagedCoopCapturedItemAuthoringService authoring) {
        if (handler == null || authoring == null) {
            throw new IllegalArgumentException("handler and authoring service are required");
        }
        Services installed = new Services(handler, authoring);
        Services current = SERVICES.get();
        if (current != null && current.handler() == handler && current.authoring() == authoring) {
            return;
        }
        if (!SERVICES.compareAndSet(null, installed)) {
            throw new IllegalStateException("managed coop item intake handler is already installed");
        }
    }

    @Nullable
    public static ManagedCoopItemIntakeHandler current() {
        Services services = SERVICES.get();
        return services != null ? services.handler() : null;
    }

    @Nullable
    public static ManagedCoopCapturedItemAuthoringService currentAuthoring() {
        Services services = SERVICES.get();
        return services != null ? services.authoring() : null;
    }

    /** Clears only the expected handler so an old shutdown cannot remove a newer runtime. */
    public static void clear(@Nonnull ManagedCoopItemIntakeHandler expected) {
        Services services = SERVICES.get();
        if (expected != null && services != null && services.handler() == expected) {
            SERVICES.compareAndSet(services, null);
        }
    }

    private record Services(@Nonnull ManagedCoopItemIntakeHandler handler,
                            @Nonnull ManagedCoopCapturedItemAuthoringService authoring) {
    }
}
