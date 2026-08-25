package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates bounded typed input before it reaches contributor authority code. */
final class CommandUiValueBounds {
    static final int MAX_DEPTH = 4;
    static final int MAX_NODES = 64;
    static final int MAX_CHILDREN = 32;
    static final int MAX_KEY_LENGTH = 64;
    static final int MAX_TOTAL_CHARACTERS = 4_096;

    private CommandUiValueBounds() {
    }

    /** Returns a stable validation result for one detached input tree. */
    @Nonnull
    static Validation validate(@Nullable CommandUiValue value) {
        if (value == null) return Validation.ok();
        Counter counter = new Counter();
        String failure = visit(value, 1, counter);
        return failure == null ? Validation.ok() : Validation.failure(failure);
    }

    @Nullable
    private static String visit(
            @Nonnull CommandUiValue value,
            int depth,
            @Nonnull Counter counter
    ) {
        if (depth > MAX_DEPTH) return "command UI input is too deep";
        if (++counter.nodes > MAX_NODES) {
            return "command UI input contains too many nodes";
        }
        switch (value.type()) {
            case STRING -> {
                String text = value.stringValue();
                if (!addCharacters(counter, text.length())) {
                    return "command UI input contains too many characters";
                }
            }
            case LIST -> {
                List<CommandUiValue> children = value.listValue();
                if (children.size() > MAX_CHILDREN) {
                    return "command UI input list contains too many children";
                }
                for (CommandUiValue child : children) {
                    String failure = visit(child, depth + 1, counter);
                    if (failure != null) return failure;
                }
            }
            case OBJECT -> {
                Map<String, CommandUiValue> children = value.objectValue();
                if (children.size() > MAX_CHILDREN) {
                    return "command UI input object contains too many children";
                }
                for (Map.Entry<String, CommandUiValue> entry : children.entrySet()) {
                    String key = entry.getKey();
                    if (key.length() > MAX_KEY_LENGTH) {
                        return "command UI input object key is too long";
                    }
                    if (!addCharacters(counter, key.length())) {
                        return "command UI input contains too many characters";
                    }
                    String failure = visit(entry.getValue(), depth + 1, counter);
                    if (failure != null) return failure;
                }
            }
            case BOOLEAN, LONG, DOUBLE -> {
                // Scalar numeric and boolean values add no string characters.
            }
        }
        return null;
    }

    private static boolean addCharacters(Counter counter, int count) {
        counter.characters += count;
        return counter.characters <= MAX_TOTAL_CHARACTERS;
    }

    record Validation(boolean valid, @Nonnull String message) {
        private static Validation ok() {
            return new Validation(true, "");
        }

        private static Validation failure(String message) {
            return new Validation(false, message);
        }
    }

    private static final class Counter {
        private int nodes;
        private int characters;
    }
}
