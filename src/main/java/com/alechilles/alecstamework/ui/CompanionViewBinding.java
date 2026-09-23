package com.alechilles.alecstamework.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Non-null, world-thread callbacks for the ordinary flute's presentation preferences. */
public record CompanionViewBinding(Supplier<CompanionViewSettings> current,
                                   Supplier<List<View>> views, Supplier<String> selectedId,
                                   Consumer<CompanionViewSettings> edit, Consumer<String> choose,
                                   BiConsumer<String, Boolean> save, Consumer<String> rename,
                                   Runnable delete, Runnable selectMatching,
                                   Supplier<List<IconOption>> iconOptions, Supplier<String> selectedIconItemId,
                                   Consumer<String> chooseIcon) {
    /** A named preset; its settings never determine command authority. */
    public record View(String id, String name, CompanionViewSettings settings) { }

    /** An icon-bearing item asset offered as a visual variant of this flute. */
    public record IconOption(String itemId, String label, String iconPath) { }
}
