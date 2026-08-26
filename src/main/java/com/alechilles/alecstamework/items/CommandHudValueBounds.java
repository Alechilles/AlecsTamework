package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates command HUD values with the command UI page limits. */
final class CommandHudValueBounds {
    static final int MAX_CONTRIBUTION_DEPTH = 8;
    static final int MAX_CONTRIBUTION_NODES = 2_048;
    static final int MAX_CONTRIBUTION_CHILDREN = 256;
    static final int MAX_CONTRIBUTION_KEY_LENGTH = 128;
    static final int MAX_CONTRIBUTION_TOTAL_CHARACTERS = 65_536;

    /** Compatibility aliases for callers that use the short bound names. */
    static final int MAX_DEPTH = MAX_CONTRIBUTION_DEPTH;
    static final int MAX_NODES = MAX_CONTRIBUTION_NODES;
    static final int MAX_CHILDREN = MAX_CONTRIBUTION_CHILDREN;
    static final int MAX_KEY_LENGTH = MAX_CONTRIBUTION_KEY_LENGTH;
    static final int MAX_TOTAL_CHARACTERS = MAX_CONTRIBUTION_TOTAL_CHARACTERS;

    private CommandHudValueBounds() {
    }

    /** Validates one HUD value as a page-root value. */
    @Nonnull
    static Validation validate(@Nullable CommandUiValue value) {
        if (value == null) return Validation.ok();
        Counter counter = new Counter();
        String failure = visit(value, 1, counter);
        return failure == null ? Validation.ok() : Validation.failure(failure);
    }

    /** Validates all values returned by one HUD contributor. */
    @Nonnull
    static Validation validateContribution(@Nonnull CommandHudContribution contribution) {
        if (contribution == null) {
            return Validation.failure("command HUD contribution is null");
        }
        Counter counter = new Counter();
        String failure = visitMap(contribution.data(), counter);
        return failure == null ? Validation.ok() : Validation.failure(failure);
    }

    /** Alias used by callers that name the operation after the value payload. */
    @Nonnull
    static Validation validateValues(@Nonnull CommandHudContribution contribution) {
        return validateContribution(contribution);
    }

    @Nullable
    private static String visitMap(
            @Nonnull Map<String, CommandUiValue> values,
            @Nonnull Counter counter
    ) {
        if (values.size() > MAX_CONTRIBUTION_CHILDREN) {
            return "command HUD contribution contains too many children";
        }
        for (Entry<String, CommandUiValue> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > MAX_CONTRIBUTION_KEY_LENGTH) {
                return "command HUD contribution key is too long";
            }
            if (!addCharacters(counter, key.length())) {
                return "command HUD contribution contains too many characters";
            }
            String failure = visit(entry.getValue(), 1, counter);
            if (failure != null) return failure;
        }
        return null;
    }

    @Nullable
    private static String visit(
            @Nonnull CommandUiValue value,
            int depth,
            @Nonnull Counter counter
    ) {
        if (value == null) return "command HUD contribution contains a null value";
        if (depth > MAX_CONTRIBUTION_DEPTH) {
            return "command HUD contribution is too deep";
        }
        if (++counter.nodes > MAX_CONTRIBUTION_NODES) {
            return "command HUD contribution contains too many nodes";
        }
        return switch (value.type()) {
            case STRING -> addCharacters(counter, value.stringValue().length())
                    ? null : "command HUD contribution contains too many characters";
            case LIST -> visitList(value.listValue(), depth, counter);
            case OBJECT -> visitObject(value.objectValue(), depth, counter);
            case BOOLEAN, LONG, DOUBLE -> null;
        };
    }

    @Nullable
    private static String visitList(
            @Nonnull List<CommandUiValue> values,
            int depth,
            @Nonnull Counter counter
    ) {
        if (values.size() > MAX_CONTRIBUTION_CHILDREN) {
            return "command HUD contribution list contains too many children";
        }
        for (CommandUiValue child : values) {
            String failure = visit(child, depth + 1, counter);
            if (failure != null) return failure;
        }
        return null;
    }

    @Nullable
    private static String visitObject(
            @Nonnull Map<String, CommandUiValue> values,
            int depth,
            @Nonnull Counter counter
    ) {
        if (values.size() > MAX_CONTRIBUTION_CHILDREN) {
            return "command HUD contribution object contains too many children";
        }
        for (Entry<String, CommandUiValue> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > MAX_CONTRIBUTION_KEY_LENGTH) {
                return "command HUD contribution object key is too long";
            }
            if (!addCharacters(counter, key.length())) {
                return "command HUD contribution contains too many characters";
            }
            String failure = visit(entry.getValue(), depth + 1, counter);
            if (failure != null) return failure;
        }
        return null;
    }

    private static boolean addCharacters(@Nonnull Counter counter, int count) {
        counter.characters += count;
        return counter.characters <= MAX_CONTRIBUTION_TOTAL_CHARACTERS;
    }

    record Validation(boolean valid, @Nonnull String message) {
        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation failure(@Nonnull String message) {
            return new Validation(false, message);
        }
    }

    private static final class Counter {
        private int nodes;
        private long characters;
    }
}
