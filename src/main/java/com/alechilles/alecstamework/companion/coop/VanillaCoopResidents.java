package com.alechilles.alecstamework.companion.coop;

import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.logger.HytaleLogger;
import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The engine's own resident list on a coop block. {@code CoopBlock} has no getter for it and its
 * only public way to change it adds residents, so {@link HytaleCoopResidents} reaches it here to
 * empty it on managed coops.
 */
final class VanillaCoopResidents {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    @Nullable private static final Field RESIDENTS = residentsField();

    private VanillaCoopResidents() {
    }

    /** The block's live, mutable list; null when this engine version does not expose it. World thread only. */
    @Nullable
    @SuppressWarnings("unchecked")
    static List<CoopBlock.CoopResident> of(@Nonnull CoopBlock coop) {
        if (RESIDENTS == null) {
            return null;
        }
        try {
            return (List<CoopBlock.CoopResident>) RESIDENTS.get(coop);
        } catch (IllegalAccessException unreadable) {
            return null;
        }
    }

    @Nullable
    private static Field residentsField() {
        try {
            Field field = CoopBlock.class.getDeclaredField("residents");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            LOGGER.at(Level.WARNING).withCause(unavailable).log(
                    "Cannot read CoopBlock.residents: vanilla residents of managed coops stay active");
            return null;
        }
    }
}
