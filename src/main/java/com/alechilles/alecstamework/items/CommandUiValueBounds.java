package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates bounded typed command UI input and contributor presentation data. */
final class CommandUiValueBounds {
    static final int MAX_ACTION_DEPTH = 4;
    static final int MAX_ACTION_NODES = 64;
    static final int MAX_ACTION_CHILDREN = 32;
    static final int MAX_ACTION_KEY_LENGTH = 64;
    static final int MAX_ACTION_TOTAL_CHARACTERS = 4_096;

    static final int MAX_CONTRIBUTION_DEPTH = 8;
    static final int MAX_CONTRIBUTION_NODES = 2_048;
    static final int MAX_CONTRIBUTION_CHILDREN = 256;
    static final int MAX_CONTRIBUTION_KEY_LENGTH = 128;
    static final int MAX_CONTRIBUTION_TOTAL_CHARACTERS = 65_536;

    /** Compatibility aliases for the original action-input validator. */
    static final int MAX_DEPTH = MAX_ACTION_DEPTH;
    static final int MAX_NODES = MAX_ACTION_NODES;
    static final int MAX_CHILDREN = MAX_ACTION_CHILDREN;
    static final int MAX_KEY_LENGTH = MAX_ACTION_KEY_LENGTH;
    static final int MAX_TOTAL_CHARACTERS = MAX_ACTION_TOTAL_CHARACTERS;

    private CommandUiValueBounds() {
    }

    /**
     * Returns a stable validation result for one detached action-input tree.
     *
     * <p>The root value has depth one. Therefore a value at the configured
     * maximum depth is valid and its child at the next depth is not.</p>
     */
    @Nonnull
    static Validation validate(@Nullable CommandUiValue value) {
        return validateActionInput(value);
    }

    /** Returns a stable validation result for one bounded action-input tree. */
    @Nonnull
    static Validation validateActionInput(@Nullable CommandUiValue value) {
        if (value == null) return Validation.ok();
        Counter counter = new Counter();
        String failure = visit(value, 1, counter, Limits.ACTION);
        return failure == null ? Validation.ok() : Validation.failure(failure);
    }

    /**
     * Validates all detached value maps in one contributor contribution.
     *
     * <p>Each page or row value is a root at depth one. The surrounding Java
     * maps are not value nodes, but their string keys count toward the shared
     * character limit. The row map itself is keyed by UUIDs and does not add
     * string keys.</p>
     */
    @Nonnull
    static Validation validateContribution(
            @Nonnull CommandUiContribution contribution
    ) {
        Counter counter = new Counter();
        String failure = visitMap(contribution.pageData(), counter,
                Limits.CONTRIBUTION, "contribution page data");
        if (failure != null) return Validation.failure(failure);
        if (contribution.rowData().size()
                > Limits.CONTRIBUTION.maxChildren()) {
            return Validation.failure(
                    "contribution row data contains too many rows");
        }
        for (Entry<UUID, Map<String, CommandUiValue>> row
                : contribution.rowData().entrySet()) {
            failure = visitMap(row.getValue(), counter, Limits.CONTRIBUTION,
                    "contribution row data");
            if (failure != null) return Validation.failure(failure);
        }
        return Validation.ok();
    }

    @Nullable
    private static String visitMap(
            @Nonnull Map<String, CommandUiValue> values,
            @Nonnull Counter counter,
            @Nonnull Limits limits,
            @Nonnull String field
    ) {
        if (values.size() > limits.maxChildren()) {
            return field + " contains too many children";
        }
        for (Entry<String, CommandUiValue> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key.length() > limits.maxKeyLength()) {
                return field + " key is too long";
            }
            if (!addCharacters(counter, key.length(), limits)) {
                return field + " contains too many characters";
            }
            String failure = visit(entry.getValue(), 1, counter, limits);
            if (failure != null) return failure;
        }
        return null;
    }

    @Nullable
    private static String visit(
            @Nonnull CommandUiValue value,
            int depth,
            @Nonnull Counter counter,
            @Nonnull Limits limits
    ) {
        if (depth > limits.maxDepth()) {
            return limits.failurePrefix() + " is too deep";
        }
        if (++counter.nodes > limits.maxNodes()) {
            return limits.failurePrefix() + " contains too many nodes";
        }
        switch (value.type()) {
            case STRING -> {
                String text = value.stringValue();
                if (!addCharacters(counter, text.length(), limits)) {
                    return limits.failurePrefix() + " contains too many characters";
                }
            }
            case LIST -> {
                List<CommandUiValue> children = value.listValue();
                if (children.size() > limits.maxChildren()) {
                    return limits.failurePrefix() + " list contains too many children";
                }
                for (CommandUiValue child : children) {
                    String failure = visit(child, depth + 1, counter, limits);
                    if (failure != null) return failure;
                }
            }
            case OBJECT -> {
                Map<String, CommandUiValue> children = value.objectValue();
                if (children.size() > limits.maxChildren()) {
                    return limits.failurePrefix() + " object contains too many children";
                }
                for (Entry<String, CommandUiValue> entry : children.entrySet()) {
                    String key = entry.getKey();
                    if (key.length() > limits.maxKeyLength()) {
                        return limits.failurePrefix() + " object key is too long";
                    }
                    if (!addCharacters(counter, key.length(), limits)) {
                        return limits.failurePrefix() + " contains too many characters";
                    }
                    String failure = visit(entry.getValue(), depth + 1, counter, limits);
                    if (failure != null) return failure;
                }
            }
            case BOOLEAN, LONG, DOUBLE -> {
                // Scalar numeric and boolean values add no string characters.
            }
        }
        return null;
    }

    private static boolean addCharacters(
            @Nonnull Counter counter,
            int count,
            @Nonnull Limits limits
    ) {
        counter.characters += count;
        return counter.characters <= limits.maxTotalCharacters();
    }

    record Validation(boolean valid, @Nonnull String message) {
        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation failure(String message) {
            return new Validation(false, message);
        }
    }

    private enum Limits {
        ACTION(
                MAX_ACTION_DEPTH,
                MAX_ACTION_NODES,
                MAX_ACTION_CHILDREN,
                MAX_ACTION_KEY_LENGTH,
                MAX_ACTION_TOTAL_CHARACTERS,
                "command UI input"
        ),
        CONTRIBUTION(
                MAX_CONTRIBUTION_DEPTH,
                MAX_CONTRIBUTION_NODES,
                MAX_CONTRIBUTION_CHILDREN,
                MAX_CONTRIBUTION_KEY_LENGTH,
                MAX_CONTRIBUTION_TOTAL_CHARACTERS,
                "command UI contribution"
        );

        private final int maxDepth;
        private final int maxNodes;
        private final int maxChildren;
        private final int maxKeyLength;
        private final int maxTotalCharacters;
        private final String failurePrefix;

        Limits(
                int maxDepth,
                int maxNodes,
                int maxChildren,
                int maxKeyLength,
                int maxTotalCharacters,
                String failurePrefix
        ) {
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
            this.maxChildren = maxChildren;
            this.maxKeyLength = maxKeyLength;
            this.maxTotalCharacters = maxTotalCharacters;
            this.failurePrefix = failurePrefix;
        }

        int maxDepth() {
            return maxDepth;
        }

        int maxNodes() {
            return maxNodes;
        }

        int maxChildren() {
            return maxChildren;
        }

        int maxKeyLength() {
            return maxKeyLength;
        }

        int maxTotalCharacters() {
            return maxTotalCharacters;
        }

        String failurePrefix() {
            return failurePrefix;
        }
    }

    private static final class Counter {
        private int nodes;
        private long characters;
    }
}
