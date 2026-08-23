package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detached presentation of one companion card.
 *
 * <p>The row contains stable IDs and display facts only. It never retains a
 * live NPC, ECS reference, profile object, callback, or mutable component.</p>
 */
public final class CommandUiCompanionRow {
    private final UUID rowId;
    @Nullable
    private final UUID companionUuid;
    @Nullable
    private final String profileId;
    private final String displayName;
    @Nullable
    private final String role;
    @Nullable
    private final String species;
    @Nullable
    private final String gender;
    @Nullable
    private final String lifecycleStatus;
    private final boolean linked;
    private final boolean active;
    private final boolean locationAvailable;
    private final boolean currentWorld;
    @Nullable
    private final Integer currentHealth;
    @Nullable
    private final Integer maxHealth;
    @Nullable
    private final Integer currentHappiness;
    @Nullable
    private final Integer maxHappiness;
    @Nonnull
    private final Map<String, CommandUiActionView> actions;
    @Nonnull
    private final Map<String, String> presentation;

    /** Creates a minimal row identified by a companion UUID. */
    public CommandUiCompanionRow(
            @Nonnull UUID companionUuid,
            @Nonnull String displayName
    ) {
        this(companionUuid, companionUuid, null, displayName, null, null, null,
                null, false, false, false, false, null, null, null, null,
                Map.of(), Map.of());
    }

    /** Creates a minimal row with an explicit lifecycle label. */
    public CommandUiCompanionRow(
            @Nonnull UUID companionUuid,
            @Nonnull String displayName,
            @Nullable String role,
            @Nullable String lifecycleStatus
    ) {
        this(companionUuid, companionUuid, null, displayName, role, null, null,
                lifecycleStatus, false, false, false, false, null, null, null,
                null, Map.of(), Map.of());
    }

    /** Full detached companion row constructor. */
    public CommandUiCompanionRow(
            @Nonnull UUID rowId,
            @Nullable UUID companionUuid,
            @Nullable String profileId,
            @Nonnull String displayName,
            @Nullable String role,
            @Nullable String species,
            @Nullable String gender,
            @Nullable String lifecycleStatus,
            boolean linked,
            boolean active,
            boolean locationAvailable,
            boolean currentWorld,
            @Nullable Integer currentHealth,
            @Nullable Integer maxHealth,
            @Nullable Integer currentHappiness,
            @Nullable Integer maxHappiness,
            @Nullable Map<String, CommandUiActionView> actions,
            @Nullable Map<String, String> presentation
    ) {
        this.rowId = Objects.requireNonNull(rowId, "rowId");
        this.companionUuid = companionUuid;
        this.profileId = normalize(profileId);
        this.displayName = requireText(displayName, "displayName");
        this.role = normalize(role);
        this.species = normalize(species);
        this.gender = normalize(gender);
        this.lifecycleStatus = normalize(lifecycleStatus);
        this.linked = linked;
        this.active = active;
        this.locationAvailable = locationAvailable;
        this.currentWorld = currentWorld;
        this.currentHealth = currentHealth;
        this.maxHealth = maxHealth;
        this.currentHappiness = currentHappiness;
        this.maxHappiness = maxHappiness;
        this.actions = copyActions(actions);
        this.presentation = copyPresentation(presentation);
    }

    /** Full row form without optional health values. */
    public CommandUiCompanionRow(
            @Nonnull UUID rowId,
            @Nullable UUID companionUuid,
            @Nullable String profileId,
            @Nonnull String displayName,
            @Nullable String role,
            @Nullable String species,
            @Nullable String gender,
            @Nullable String lifecycleStatus,
            boolean linked,
            boolean active,
            boolean locationAvailable,
            boolean currentWorld,
            @Nullable Map<String, CommandUiActionView> actions
    ) {
        this(rowId, companionUuid, profileId, displayName, role, species, gender,
                lifecycleStatus, linked, active, locationAvailable, currentWorld,
                null, null, null, null, actions, Map.of());
    }

    @Nonnull
    public UUID rowId() {
        return rowId;
    }

    /** Alias used by row-level change hints. */
    @Nonnull
    public UUID id() {
        return rowId;
    }

    @Nullable
    public UUID companionUuid() {
        return companionUuid;
    }

    /** Alias matching the existing linked-panel model. */
    @Nullable
    public UUID npcUuid() {
        return companionUuid;
    }

    /** Alias for the optional current-world identity wording. */
    @Nullable
    public UUID currentNpcUuid() {
        return companionUuid;
    }

    @Nullable
    public String profileId() {
        return profileId;
    }

    @Nonnull
    public String displayName() {
        return displayName;
    }

    @Nullable
    public String role() {
        return role;
    }

    @Nullable
    public String roleId() {
        return role;
    }

    @Nullable
    public String species() {
        return species;
    }

    @Nullable
    public String speciesId() {
        return species;
    }

    @Nullable
    public String gender() {
        return gender;
    }

    @Nullable
    public String lifecycleStatus() {
        return lifecycleStatus;
    }

    @Nullable
    public String status() {
        return lifecycleStatus;
    }

    public boolean linked() {
        return linked;
    }

    public boolean active() {
        return active;
    }

    public boolean locationAvailable() {
        return locationAvailable;
    }

    public boolean currentWorld() {
        return currentWorld;
    }

    @Nullable
    public Integer currentHealth() {
        return currentHealth;
    }

    @Nullable
    public Integer maxHealth() {
        return maxHealth;
    }

    @Nullable
    public Integer currentHappiness() {
        return currentHappiness;
    }

    @Nullable
    public Integer maxHappiness() {
        return maxHappiness;
    }

    @Nonnull
    public Map<String, CommandUiActionView> actions() {
        return actions;
    }

    @Nonnull
    public Map<String, CommandUiActionView> actionViews() {
        return actions;
    }

    @Nullable
    public CommandUiActionView action(@Nullable String kind) {
        return kind == null ? null : actions.get(kind);
    }

    @Nonnull
    public Map<String, String> presentation() {
        return presentation;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiCompanionRow that)) return false;
        return linked == that.linked
                && active == that.active
                && locationAvailable == that.locationAvailable
                && currentWorld == that.currentWorld
                && Objects.equals(rowId, that.rowId)
                && Objects.equals(companionUuid, that.companionUuid)
                && Objects.equals(profileId, that.profileId)
                && displayName.equals(that.displayName)
                && Objects.equals(role, that.role)
                && Objects.equals(species, that.species)
                && Objects.equals(gender, that.gender)
                && Objects.equals(lifecycleStatus, that.lifecycleStatus)
                && Objects.equals(currentHealth, that.currentHealth)
                && Objects.equals(maxHealth, that.maxHealth)
                && Objects.equals(currentHappiness, that.currentHappiness)
                && Objects.equals(maxHappiness, that.maxHappiness)
                && actions.equals(that.actions)
                && presentation.equals(that.presentation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rowId, companionUuid, profileId, displayName, role,
                species, gender, lifecycleStatus, linked, active,
                locationAvailable, currentWorld, currentHealth, maxHealth,
                currentHappiness, maxHappiness, actions, presentation);
    }

    @Nonnull
    private static Map<String, CommandUiActionView> copyActions(
            @Nullable Map<String, CommandUiActionView> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, CommandUiActionView> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static Map<String, String> copyPresentation(
            @Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
