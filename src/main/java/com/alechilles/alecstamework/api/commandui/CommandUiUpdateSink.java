package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Guarded sink for provider-requested snapshot/page updates.
 *
 * <p>The host supplies the implementation. Providers do not receive a page
 * manager and cannot bypass Tamework's session lifecycle.</p>
 */
public interface CommandUiUpdateSink {
    /** Publishes one immutable update when the owning session is open. */
    boolean publish(@Nonnull CommandUiUpdate update);

    /** Alias for page controllers that call this operation {@code update}. */
    default boolean update(@Nonnull CommandUiUpdate update) {
        return publish(update);
    }

    /** Alias for adapters that call publication submission. */
    default boolean submit(@Nonnull CommandUiUpdate update) {
        return publish(update);
    }

    /** Requests a source refresh through Tamework's coordinator. */
    default boolean requestRefresh() {
        return false;
    }

    /** Returns whether updates can still be accepted. */
    default boolean open() {
        return true;
    }

    /** Stable closed sink for degraded adapters. */
    @Nonnull
    static CommandUiUpdateSink unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final CommandUiUpdateSink INSTANCE = new CommandUiUpdateSink() {
            @Override
            public boolean publish(CommandUiUpdate update) {
                Objects.requireNonNull(update, "update");
                return false;
            }

            @Override
            public boolean open() {
                return false;
            }
        };

        private UnavailableHolder() {
        }
    }
}
