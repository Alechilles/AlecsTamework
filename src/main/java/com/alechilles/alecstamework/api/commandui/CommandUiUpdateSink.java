package com.alechilles.alecstamework.api.commandui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Guarded provider request sink for refreshes and host-owned UI updates. */
public interface CommandUiUpdateSink {
    /** Requests a Tamework-owned snapshot refresh. */
    boolean requestRefresh();

    /** Submits a guarded partial page update; {@code clear} is normally false. */
    boolean submit(
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            boolean clear
    );

    /** Returns whether the host still accepts requests. */
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
            public boolean requestRefresh() {
                return false;
            }

            @Override
            public boolean submit(UICommandBuilder commandBuilder,
                                  UIEventBuilder eventBuilder,
                                  boolean clear) {
                Objects.requireNonNull(commandBuilder, "commandBuilder");
                Objects.requireNonNull(eventBuilder, "eventBuilder");
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
