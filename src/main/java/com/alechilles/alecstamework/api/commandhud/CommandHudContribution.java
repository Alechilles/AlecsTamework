package com.alechilles.alecstamework.api.commandhud;

import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable namespaced data returned by one command HUD contributor. */
public final class CommandHudContribution {
    private final CommandHudContributorId contributorId;
    private final Map<String, CommandUiValue> data;
    private final CommandHudContributionStatus status;
    private final String diagnosticReason;

    /** Creates an available contribution with detached data. */
    public CommandHudContribution(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable Map<String, CommandUiValue> data
    ) {
        this(contributorId, data, CommandHudContributionStatus.AVAILABLE, null);
    }

    /** Creates a contribution with an explicit status and diagnostic reason. */
    public CommandHudContribution(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable Map<String, CommandUiValue> data,
            @Nonnull CommandHudContributionStatus status,
            @Nullable String diagnosticReason
    ) {
        this.contributorId = Objects.requireNonNull(contributorId, "contributorId");
        this.data = copyData(data);
        this.status = Objects.requireNonNull(status, "status");
        this.diagnosticReason = normalize(diagnosticReason);
    }

    /** Creates an available contribution. */
    @Nonnull
    public static CommandHudContribution available(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable Map<String, CommandUiValue> data
    ) {
        return new CommandHudContribution(contributorId, data,
                CommandHudContributionStatus.AVAILABLE, null);
    }

    /** Creates an unavailable contribution without data. */
    @Nonnull
    public static CommandHudContribution unavailable(
            @Nonnull CommandHudContributorId contributorId
    ) {
        return unavailable(contributorId, null);
    }

    /** Creates an unavailable contribution with a safe diagnostic reason. */
    @Nonnull
    public static CommandHudContribution unavailable(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable String reason
    ) {
        return new CommandHudContribution(contributorId, Map.of(),
                CommandHudContributionStatus.UNAVAILABLE, reason);
    }

    /** Creates a failed contribution without exposing an exception object. */
    @Nonnull
    public static CommandHudContribution failed(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable String reason
    ) {
        return new CommandHudContribution(contributorId, Map.of(),
                CommandHudContributionStatus.FAILED, reason);
    }

    /** Creates a contribution that the selected renderer cannot display. */
    @Nonnull
    public static CommandHudContribution unsupported(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable String reason
    ) {
        return new CommandHudContribution(contributorId, Map.of(),
                CommandHudContributionStatus.UNSUPPORTED_BY_RENDERER, reason);
    }

    @Nonnull
    public CommandHudContributorId contributorId() {
        return contributorId;
    }

    /** Returns immutable contributor-local data. */
    @Nonnull
    public Map<String, CommandUiValue> data() {
        return data;
    }

    /** Returns one contributor-local value, or null when absent. */
    @Nullable
    public CommandUiValue value(@Nullable String path) {
        return path == null ? null : data.get(path);
    }

    @Nonnull
    public CommandHudContributionStatus status() {
        return status;
    }

    /** Returns a redacted, human-readable diagnostic reason. */
    @Nonnull
    public String diagnosticReason() {
        return diagnosticReason;
    }

    public boolean available() {
        return status == CommandHudContributionStatus.AVAILABLE;
    }

    @Nonnull
    private static Map<String, CommandUiValue> copyData(
            @Nullable Map<String, CommandUiValue> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, CommandUiValue> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = Objects.requireNonNull(key, "data key").trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Contribution data keys must be nonblank.");
            }
            copy.put(normalized, Objects.requireNonNull(value, "data value"));
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandHudContribution that)) return false;
        return contributorId.equals(that.contributorId)
                && data.equals(that.data)
                && status == that.status
                && diagnosticReason.equals(that.diagnosticReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contributorId, data, status, diagnosticReason);
    }

    @Override
    public String toString() {
        return "CommandHudContribution[contributorId=" + contributorId
                + ", status=" + status + ", dataKeys=" + data.keySet() + "]";
    }
}
