package com.alechilles.alecstamework.api.commandui;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached presentation and purchase action for one talent node. */
public final class CommandUiTalentNodeView {
    private final String talentId;
    private final String branchName;
    private final int tier;
    private final String state;
    private final String displayName;
    private final String description;
    private final String status;
    private final int pointCost;
    private final int minimumLevel;
    private final List<String> requiredTalentIds;
    private final List<String> requiredTalentNames;
    private final String effectSummary;
    @Nullable private final CommandUiActionView purchaseAction;

    public CommandUiTalentNodeView(
            @Nonnull String talentId,
            @Nonnull String branchName,
            int tier,
            @Nonnull String state,
            @Nonnull String displayName,
            @Nullable String description,
            @Nullable String status,
            int pointCost,
            int minimumLevel,
            @Nullable List<String> requiredTalentIds,
            @Nullable List<String> requiredTalentNames,
            @Nullable String effectSummary,
            @Nullable CommandUiActionView purchaseAction
    ) {
        this.talentId = requireText(talentId, "talentId");
        this.branchName = requireText(branchName, "branchName");
        this.tier = Math.max(0, tier);
        this.state = requireText(state, "state");
        this.displayName = requireText(displayName, "displayName");
        this.description = description == null ? "" : description;
        this.status = status == null ? "" : status;
        this.pointCost = Math.max(0, pointCost);
        this.minimumLevel = Math.max(0, minimumLevel);
        this.requiredTalentIds = List.copyOf(requiredTalentIds == null
                ? List.of() : requiredTalentIds);
        this.requiredTalentNames = List.copyOf(requiredTalentNames == null
                ? List.of() : requiredTalentNames);
        this.effectSummary = effectSummary == null ? "" : effectSummary;
        this.purchaseAction = purchaseAction;
    }

    @Nonnull public String talentId() { return talentId; }
    @Nonnull public String branchName() { return branchName; }
    public int tier() { return tier; }
    @Nonnull public String state() { return state; }
    @Nonnull public String displayName() { return displayName; }
    @Nonnull public String description() { return description; }
    @Nonnull public String status() { return status; }
    public int pointCost() { return pointCost; }
    public int minimumLevel() { return minimumLevel; }
    @Nonnull public List<String> requiredTalentIds() { return requiredTalentIds; }
    @Nonnull public List<String> requiredTalentNames() { return requiredTalentNames; }
    @Nonnull public String effectSummary() { return effectSummary; }
    @Nullable public CommandUiActionView purchaseAction() { return purchaseAction; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiTalentNodeView that)) return false;
        return tier == that.tier && pointCost == that.pointCost
                && minimumLevel == that.minimumLevel
                && talentId.equals(that.talentId)
                && branchName.equals(that.branchName)
                && state.equals(that.state)
                && displayName.equals(that.displayName)
                && description.equals(that.description)
                && status.equals(that.status)
                && requiredTalentIds.equals(that.requiredTalentIds)
                && requiredTalentNames.equals(that.requiredTalentNames)
                && effectSummary.equals(that.effectSummary)
                && Objects.equals(purchaseAction, that.purchaseAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(talentId, branchName, tier, state, displayName,
                description, status, pointCost, minimumLevel,
                requiredTalentIds, requiredTalentNames, effectSummary,
                purchaseAction);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
