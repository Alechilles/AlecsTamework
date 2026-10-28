package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.AvatarFlightLoanEnded;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Listeners for ended loans. Process-wide because every lifecycle service instance ends loans;
 * the API facade clears it when it closes.
 */
public final class AvatarFlightLoanListeners {
    private static final List<Consumer<AvatarFlightLoanEnded>> LISTENERS = new CopyOnWriteArrayList<>();

    private AvatarFlightLoanListeners() {
    }

    @Nonnull
    public static AutoCloseable register(@Nonnull Consumer<AvatarFlightLoanEnded> listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static void clear() {
        LISTENERS.clear();
    }

    /** One listener's failure is logged and does not stop the others or the teardown. */
    static void publish(@Nonnull AvatarFlightLoanEnded ended) {
        for (Consumer<AvatarFlightLoanEnded> listener : LISTENERS) {
            try {
                listener.accept(ended);
            } catch (RuntimeException exception) {
                Tamework plugin = Tamework.getInstance();
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().at(Level.WARNING).withCause(exception).log(
                            "TameworkAvatarFlight loan listener failed: lender=%s player=%s",
                            ended.loan().lenderId(), ended.loan().playerId());
                }
            }
        }
    }
}
