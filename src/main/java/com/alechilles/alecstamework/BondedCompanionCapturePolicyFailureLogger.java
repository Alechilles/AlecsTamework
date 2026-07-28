package com.alechilles.alecstamework;

import com.alechilles.alecstamework.items.BondedCompanionCaptureIntent;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Writes the optional diagnostic for capture-policy failures without expanding composition. */
final class BondedCompanionCapturePolicyFailureLogger {
    private BondedCompanionCapturePolicyFailureLogger() {
    }

    static void log(
            @Nullable HytaleLogger logger,
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable RuntimeException failure
    ) {
        if (logger == null) return;
        var entry = logger.at(Level.WARNING);
        if (failure != null) entry = entry.withCause(failure);
        entry.log("Bonded capture policy unavailable (roster="
                + (intent == null ? null : intent.rosterId()) + ", role="
                + (intent == null ? null : intent.roleId()) + ").");
    }
}
