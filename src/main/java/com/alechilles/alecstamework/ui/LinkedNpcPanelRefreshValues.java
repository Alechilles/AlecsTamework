package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Suppresses repeat UI writes when a linked-panel value has not changed. */
final class LinkedNpcPanelRefreshValues {
    private final Map<String, Object> previousValues = new HashMap<>();

    void set(@Nonnull UICommandBuilder commands, @Nonnull String selector,
             @Nonnull String value) {
        if (!changed(selector, value)) return;
        commands.set(selector, value);
    }

    void set(@Nonnull UICommandBuilder commands, @Nonnull String selector,
             boolean value) {
        if (!changed(selector, value)) return;
        commands.set(selector, value);
    }

    <T> void set(@Nonnull UICommandBuilder commands, @Nonnull String selector,
                 @Nonnull List<T> value) {
        if (!changed(selector, value)) return;
        commands.set(selector, value);
    }

    private boolean changed(String selector, Object value) {
        if (Objects.equals(previousValues.get(selector), value)
                && previousValues.containsKey(selector)) return false;
        previousValues.put(selector, value);
        return true;
    }
}
