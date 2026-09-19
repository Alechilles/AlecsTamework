package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** World-thread callbacks for ordinary owned-companion browsing and player-owned group preferences. */
public record CompanionPanelBinding(Supplier<String> state, Consumer<String> setState,
                                    Supplier<Boolean> nearby, Consumer<Boolean> setNearby,
                                    BiConsumer<UUID, List<String>> assignGroups,
                                    Consumer<String> addGroup) {
    public CompanionPanelBinding(Supplier<String> state, Consumer<String> setState,
                                 Supplier<Boolean> nearby, Consumer<Boolean> setNearby,
                                 BiConsumer<UUID, List<String>> assignGroups) {
        this(state, setState, nearby, setNearby, assignGroups, ignored -> { });
    }
}
