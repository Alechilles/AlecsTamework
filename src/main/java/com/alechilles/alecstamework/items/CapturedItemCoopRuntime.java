package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.coop.CapturedItemCoopAuthor;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopTarget;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Narrow installation point used by codec-created captured-item interactions.
 *
 * <p>Hytale constructs interaction instances through codecs, so constructor injection is not
 * available at that boundary. The installed submission function contains no persistence state
 * of its own and must be removed by the owning composition during shutdown.</p>
 */
public final class CapturedItemCoopRuntime {
    private static final AtomicReference<Submission> CURRENT =
            new AtomicReference<>();

    private CapturedItemCoopRuntime() {
    }

    /** Installs the single canonical captured-item authoring function. */
    public static void install(@Nonnull Submission submission) {
        if (submission == null
                || !CURRENT.compareAndSet(null, submission)) {
            throw new IllegalStateException(
                    "Captured-item coop runtime is already installed"
            );
        }
    }

    /** Returns the currently installed authoring function, when persistence is ready. */
    @Nullable
    public static Submission current() {
        return CURRENT.get();
    }

    /** Removes only the exact function owned by the shutting-down composition. */
    public static boolean uninstall(@Nonnull Submission submission) {
        return submission != null
                && CURRENT.compareAndSet(submission, null);
    }

    /** One facade-level submission with no Hytale store-affine values. */
    @FunctionalInterface
    public interface Submission {
        @Nonnull
        CompletionStage<CapturedItemCoopAuthor.Outcome> submit(
                @Nonnull CapturedItemCoopAuthor.Source source,
                @Nonnull CapturedItemCoopTarget target
        );
    }
}
