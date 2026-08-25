package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached companion talent state for a custom provider. */
public final class CommandUiTalentFlowView implements CommandUiFlowView {
    public static final String KIND = "talents";

    private final UUID rowId;
    @Nullable private final String profileId;
    private final String displayName;
    private final int level;
    private final int availablePoints;
    private final String levelSummary;
    private final String pointsSummary;
    private final String status;
    @Nullable private final CommandUiActionView resetAction;
    private final List<CommandUiTalentNodeView> nodes;
    private final Map<String, String> metadata;

    public CommandUiTalentFlowView(
            @Nonnull UUID rowId,
            @Nullable String profileId,
            @Nonnull String displayName,
            int level,
            int availablePoints,
            @Nullable String levelSummary,
            @Nullable String pointsSummary,
            @Nullable String status,
            @Nullable CommandUiActionView resetAction,
            @Nullable List<CommandUiTalentNodeView> nodes,
            @Nullable Map<String, String> metadata
    ) {
        this.rowId = Objects.requireNonNull(rowId, "rowId");
        this.profileId = normalize(profileId);
        this.displayName = requireText(displayName, "displayName");
        this.level = Math.max(0, level);
        this.availablePoints = Math.max(0, availablePoints);
        this.levelSummary = levelSummary == null ? "" : levelSummary;
        this.pointsSummary = pointsSummary == null ? "" : pointsSummary;
        this.status = status == null ? "" : status;
        this.resetAction = resetAction;
        this.nodes = List.copyOf(nodes == null ? List.of() : nodes);
        this.metadata = copyMetadata(metadata);
    }

    @Nonnull @Override public String kind() { return KIND; }
    @Nonnull public UUID rowId() { return rowId; }
    @Nullable public String profileId() { return profileId; }
    @Nonnull public String displayName() { return displayName; }
    public int level() { return level; }
    public int availablePoints() { return availablePoints; }
    @Nonnull public String levelSummary() { return levelSummary; }
    @Nonnull public String pointsSummary() { return pointsSummary; }
    @Nonnull public String status() { return status; }
    @Nullable public CommandUiActionView resetAction() { return resetAction; }
    @Nonnull public List<CommandUiTalentNodeView> nodes() { return nodes; }
    @Nonnull public Map<String, String> metadata() { return metadata; }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    @Nonnull
    private static Map<String, String> copyMetadata(
            @Nullable Map<String, String> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
