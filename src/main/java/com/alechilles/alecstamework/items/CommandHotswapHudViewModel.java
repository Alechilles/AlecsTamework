package com.alechilles.alecstamework.items;

import javax.annotation.Nonnull;

/** Immutable presentation state for the three equipped command-flute hotswaps. */
public record CommandHotswapHudViewModel(@Nonnull Slot primary,
                                         @Nonnull Slot secondary,
                                         @Nonnull Slot q,
                                         @Nonnull Slot e,
                                         @Nonnull Slot r,
                                         @Nonnull GroupStatus groupStatus) {
    public CommandHotswapHudViewModel {
        primary = primary == null ? Slot.hidden("LMB") : primary;
        secondary = secondary == null ? Slot.hidden("RMB") : secondary;
        q = q == null ? Slot.hidden("Q") : q;
        e = e == null ? Slot.hidden("E") : e;
        r = r == null ? Slot.hidden("R") : r;
        groupStatus = groupStatus == null ? GroupStatus.hidden() : groupStatus;
    }

    public boolean visible() {
        return primary.visible() || secondary.visible() || q.visible() || e.visible() || r.visible();
    }

    /** Presentation for one fixed game binding. */
    public record Slot(boolean visible,
                       @Nonnull String bindingLabel,
                       @Nonnull String iconTexturePath,
                       @Nonnull String fallbackGlyph) {
        public Slot {
            bindingLabel = bindingLabel == null ? "" : bindingLabel;
            iconTexturePath = iconTexturePath == null ? "" : iconTexturePath;
            fallbackGlyph = fallbackGlyph == null ? "" : fallbackGlyph;
        }

        @Nonnull
        public static Slot hidden(@Nonnull String bindingLabel) {
            return new Slot(false, bindingLabel, "", "");
        }

        public boolean hasIconTexturePath() {
            return !iconTexturePath.isEmpty();
        }
    }

    /** Presentation for the active generic command recipient scope. */
    public record GroupStatus(boolean visible,
                              @Nonnull String label,
                              @Nonnull String colorHex) {
        public GroupStatus {
            label = label == null ? "" : label;
            colorHex = colorHex == null ? "#c8d1db" : colorHex;
        }

        @Nonnull
        public static GroupStatus hidden() {
            return new GroupStatus(false, "", "#c8d1db");
        }
    }
}
