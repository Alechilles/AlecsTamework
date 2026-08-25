package com.alechilles.alecstamework.api.commandui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable, generic value-tree view for one contributor-owned command UI
 * flow.
 *
 * <p>Tamework binds flow action handles to the instance, owner generation,
 * revision, and action generation. A renderer receives this detached view and
 * may use its data and actions for layout, but cannot extend the authority of
 * an expired revision.</p>
 */
public final class CommandUiCustomFlowView implements CommandUiFlowView {
    private static final Pattern NAMESPACED_TYPE = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*");

    private final UUID flowInstanceId;
    private final String flowType;
    private final CommandUiContributorId ownerContributorId;
    private final long ownerGeneration;
    private final long revision;
    private final long actionGeneration;
    private final Map<String, CommandUiValue> data;
    private final Map<String, CommandUiActionView> actions;

    /** Creates one immutable contributor-owned custom flow view. */
    public CommandUiCustomFlowView(
            @Nonnull UUID flowInstanceId,
            @Nonnull String flowType,
            @Nonnull CommandUiContributorId ownerContributorId,
            long ownerGeneration,
            long revision,
            long actionGeneration,
            @Nullable Map<String, CommandUiValue> data,
            @Nullable Map<String, CommandUiActionView> actions
    ) {
        this.flowInstanceId = Objects.requireNonNull(flowInstanceId, "flowInstanceId");
        this.flowType = requireNamespacedType(flowType);
        this.ownerContributorId = Objects.requireNonNull(
                ownerContributorId, "ownerContributorId");
        this.ownerGeneration = requirePositive(ownerGeneration, "ownerGeneration");
        this.revision = requirePositive(revision, "revision");
        this.actionGeneration = requirePositive(actionGeneration, "actionGeneration");
        this.data = copyData(data);
        this.actions = copyActions(actions);
    }

    @Nonnull
    public UUID flowInstanceId() {
        return flowInstanceId;
    }

    /** Returns the normalized public namespaced flow type. */
    @Nonnull
    public String flowType() {
        return flowType;
    }

    @Nonnull
    public CommandUiContributorId ownerContributorId() {
        return ownerContributorId;
    }

    public long ownerGeneration() {
        return ownerGeneration;
    }

    public long revision() {
        return revision;
    }

    public long actionGeneration() {
        return actionGeneration;
    }

    /** Returns immutable contributor-defined presentation values. */
    @Nonnull
    public Map<String, CommandUiValue> data() {
        return data;
    }

    /** Returns immutable flow actions keyed by their effective public IDs. */
    @Nonnull
    public Map<String, CommandUiActionView> actions() {
        return actions;
    }

    /** Returns the namespaced type through the shared flow-view contract. */
    @Nonnull
    @Override
    public String kind() {
        return flowType;
    }

    /** Returns whether this view uses the generic custom-flow envelope. */
    @Override
    public boolean isCustom() {
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiCustomFlowView that)) return false;
        return ownerGeneration == that.ownerGeneration
                && revision == that.revision
                && actionGeneration == that.actionGeneration
                && flowInstanceId.equals(that.flowInstanceId)
                && flowType.equals(that.flowType)
                && ownerContributorId.equals(that.ownerContributorId)
                && data.equals(that.data)
                && actions.equals(that.actions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flowInstanceId, flowType, ownerContributorId,
                ownerGeneration, revision, actionGeneration, data, actions);
    }

    @Override
    public String toString() {
        return "CommandUiCustomFlowView[type=" + flowType
                + ", revision=" + revision
                + ", actionGeneration=" + actionGeneration + "]";
    }

    @Nonnull
    private static Map<String, CommandUiValue> copyData(
            @Nullable Map<String, CommandUiValue> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, CommandUiValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CommandUiValue> entry : source.entrySet()) {
            String key = requireKey(entry.getKey(), "data key");
            copy.put(key, Objects.requireNonNull(entry.getValue(), "data value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Nonnull
    private static Map<String, CommandUiActionView> copyActions(
            @Nullable Map<String, CommandUiActionView> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, CommandUiActionView> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CommandUiActionView> entry : source.entrySet()) {
            String key = requireKey(entry.getKey(), "action key");
            copy.put(key, Objects.requireNonNull(entry.getValue(), "action value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Nonnull
    private static String requireNamespacedType(@Nullable String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACED_TYPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Command UI custom flow type must be a namespaced identifier: "
                            + value);
        }
        return normalized;
    }

    @Nonnull
    private static String requireKey(@Nullable String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static long requirePositive(long value, String field) {
        if (value <= 0L) {
            throw new IllegalArgumentException(field + " must be positive.");
        }
        return value;
    }
}
