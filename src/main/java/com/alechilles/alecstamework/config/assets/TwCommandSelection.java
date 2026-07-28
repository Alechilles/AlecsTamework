package com.alechilles.alecstamework.config.assets;

import java.util.Locale;
import javax.annotation.Nullable;

/** Focused command-entry lookup and deterministic cycling for command-item assets. */
final class TwCommandSelection {
    private TwCommandSelection() {
    }

    static TwCommandItemConfig.CommandEntry createBuiltInReturnHome() {
        TwCommandItemConfig.MoveToPositionStep step =
                new TwCommandItemConfig.MoveToPositionStep();
        step.source = TwCommandItemConfig.MoveSource.StoredHome;
        TwCommandItemConfig.CommandEntry entry =
                new TwCommandItemConfig.CommandEntry();
        entry.id = "ReturnHome";
        entry.displayName = "Return Home";
        entry.steps = new TwCommandItemConfig.CommandStep[]{step};
        return entry;
    }

    @Nullable
    static TwCommandItemConfig.CommandEntry findById(
            TwCommandItemConfig.CommandEntry[] commands,
            @Nullable String commandId
    ) {
        if (!hasText(commandId) || commands == null) {
            return null;
        }
        for (TwCommandItemConfig.CommandEntry entry : commands) {
            if (entry != null && sameId(entry.id, commandId)) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    static TwCommandItemConfig.CommandEntry findDefault(
            TwCommandItemConfig.CommandEntry[] commands
    ) {
        if (commands == null) {
            return null;
        }
        for (TwCommandItemConfig.CommandEntry entry : commands) {
            if (entry != null && entry.defaultCommand) {
                return entry;
            }
        }
        for (TwCommandItemConfig.CommandEntry entry : commands) {
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    static TwCommandItemConfig.CommandEntry findNext(
            TwCommandItemConfig.CommandEntry[] commands,
            @Nullable String currentCommandId
    ) {
        if (commands == null || commands.length == 0) {
            return null;
        }
        int first = -1;
        int current = -1;
        for (int index = 0; index < commands.length; index++) {
            TwCommandItemConfig.CommandEntry entry = commands[index];
            if (entry == null || !hasText(entry.id)) {
                continue;
            }
            if (first < 0) {
                first = index;
            }
            if (sameId(entry.id, currentCommandId)) {
                current = index;
            }
        }
        if (first < 0) {
            return null;
        }
        if (current < 0) {
            return commands[first];
        }
        for (int offset = 1; offset <= commands.length; offset++) {
            TwCommandItemConfig.CommandEntry entry =
                    commands[(current + offset) % commands.length];
            if (entry != null && hasText(entry.id)) {
                return entry;
            }
        }
        return commands[current];
    }

    private static boolean sameId(
            @Nullable String left,
            @Nullable String right
    ) {
        return hasText(left) && hasText(right)
                && left.trim().toLowerCase(Locale.ROOT).equals(
                right.trim().toLowerCase(Locale.ROOT)
        );
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
