package com.alechilles.alecstamework.api.commandhud;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable, detached base presentation for the equipped command hotswaps. */
public record CommandHotswapHudSnapshot(
        @Nonnull Slot primary,
        @Nonnull Slot secondary,
        @Nonnull Slot q,
        @Nonnull Slot e,
        @Nonnull Slot r,
        @Nonnull GroupStatus groupStatus
) {
    public CommandHotswapHudSnapshot {
        primary = primary == null ? Slot.hidden("LMB") : primary;
        secondary = secondary == null ? Slot.hidden("RMB") : secondary;
        q = q == null ? Slot.hidden("Q") : q;
        e = e == null ? Slot.hidden("E") : e;
        r = r == null ? Slot.hidden("R") : r;
        groupStatus = groupStatus == null ? GroupStatus.hidden() : groupStatus;
    }

    /** Returns whether at least one hotswap slot is visible. */
    public boolean visible() {
        return primary.visible() || secondary.visible() || q.visible()
                || e.visible() || r.visible();
    }

    /** Presentation for one fixed command binding. */
    public record Slot(
            boolean visible,
            @Nonnull String bindingLabel,
            @Nonnull String iconTexturePath,
            @Nonnull String fallbackGlyph
    ) {
        public Slot {
            bindingLabel = normalize(bindingLabel);
            iconTexturePath = normalize(iconTexturePath);
            fallbackGlyph = normalize(fallbackGlyph);
        }

        /** Creates a hidden slot with no icon or fallback glyph. */
        @Nonnull
        public static Slot hidden(@Nonnull String bindingLabel) {
            return new Slot(false, bindingLabel, "", "");
        }

        public boolean hasIconTexturePath() {
            return !iconTexturePath.isEmpty();
        }
    }

    /** Presentation for the active generic command recipient scope. */
    public record GroupStatus(
            boolean visible,
            @Nonnull String label,
            @Nonnull String colorHex
    ) {
        public GroupStatus {
            label = normalize(label);
            colorHex = colorHex == null || colorHex.isBlank()
                    ? "#c8d1db" : colorHex.trim();
        }

        /** Creates hidden group status using the standard neutral color. */
        @Nonnull
        public static GroupStatus hidden() {
            return new GroupStatus(false, "", "#c8d1db");
        }
    }

    @Nonnull
    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CommandHotswapHudSnapshot that
                && primary.equals(that.primary)
                && secondary.equals(that.secondary)
                && q.equals(that.q)
                && e.equals(that.e)
                && r.equals(that.r)
                && groupStatus.equals(that.groupStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primary, secondary, q, e, r, groupStatus);
    }
}
