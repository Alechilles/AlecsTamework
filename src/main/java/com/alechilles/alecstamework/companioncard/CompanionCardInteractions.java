package com.alechilles.alecstamework.companioncard;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The interaction-driven part of a companion card, computed in the companion's role tick for one viewer: the mode
 * buttons from its {@code ModeCycle} entry and the buttons of entries that declare a {@code Card} block. Labels and
 * hints are already resolved for the viewer's language. This is a presentation snapshot: clicking a button queues a
 * {@link CompanionCardRequests} request that the role tick checks again before anything runs.
 *
 * @param configId    interaction config the snapshot came from, or {@code null} when the companion has none
 * @param modes       mode buttons in authored order; empty when the config has no usable {@code ModeCycle} entry
 * @param modeAllowed whether the viewer may change modes now (the {@code ModeCycle} entry's requirements pass)
 * @param actions     card buttons sorted by {@code Card.Order}, then authored order
 */
public record CompanionCardInteractions(@Nullable String configId,
                                        @Nonnull List<Mode> modes,
                                        boolean modeAllowed,
                                        @Nonnull List<Action> actions) {
    public static final CompanionCardInteractions EMPTY =
            new CompanionCardInteractions(null, List.of(), false, List.of());

    public CompanionCardInteractions {
        modes = List.copyOf(modes);
        actions = List.copyOf(actions);
    }

    /**
     * @param stepIndex index of the step in the config's {@code Cycle} list, used to request it
     * @param label     resolved label
     * @param icon      glyph texture path under {@code Common/UI/Custom}
     * @param current   whether the companion is in this mode now
     */
    public record Mode(int stepIndex, @Nonnull String label, @Nonnull String icon, boolean current) {
    }

    /**
     * @param entryIndex index of the entry in the config's {@code Interactions} list, used to request it
     * @param kind       entry type, so the page can close itself after a mount
     * @param label      resolved label
     * @param icon       glyph texture path under {@code Common/UI/Custom}
     * @param status     whether the button can run now
     */
    public record Action(int entryIndex, @Nonnull Kind kind, @Nonnull String label, @Nonnull String icon,
                         @Nonnull Status status) {
    }

    public enum Kind { TAME, FEED, BREED, HARVEST, MOUNT, CUSTOM }

    public enum Status {
        /** Requirements pass and no cooldown blocks it. */
        READY,
        /** Requirements pass, but the entry's cooldown or harvest timer is still running. */
        COOLDOWN,
        /** A requirement other than a crouch or movement gesture fails. */
        UNAVAILABLE
    }
}
