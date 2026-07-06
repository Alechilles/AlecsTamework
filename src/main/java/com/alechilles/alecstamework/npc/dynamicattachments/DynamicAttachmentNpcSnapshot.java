package com.alechilles.alecstamework.npc.dynamicattachments;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable snapshot of NPC state used by dynamic attachment condition evaluation. */
public final class DynamicAttachmentNpcSnapshot {
    private final String roleId;
    private final String displayName;
    private final Boolean ownerPresent;
    private final Boolean tamed;
    private final String gender;
    private final String lifeStage;
    private final Double happiness;
    private final Map<String, Double> needs;
    private final Map<String, Double> traits;
    private final Map<String, String> commandStates;

    private DynamicAttachmentNpcSnapshot(@Nonnull Builder builder) {
        roleId = blankToNull(builder.roleId);
        displayName = blankToNull(builder.displayName);
        ownerPresent = builder.ownerPresent;
        tamed = builder.tamed;
        gender = blankToNull(builder.gender);
        lifeStage = blankToNull(builder.lifeStage);
        happiness = builder.happiness;
        needs = copyNormalizedDoubleMap(builder.needs);
        traits = copyNormalizedDoubleMap(builder.traits);
        commandStates = copyNormalizedStringMap(builder.commandStates);
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    public String getRoleId() {
        return roleId;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public Boolean getOwnerPresent() {
        return ownerPresent;
    }

    @Nullable
    public Boolean getTamed() {
        return tamed;
    }

    @Nullable
    public String getGender() {
        return gender;
    }

    @Nullable
    public String getLifeStage() {
        return lifeStage;
    }

    @Nullable
    public Double getHappiness() {
        return happiness;
    }

    @Nonnull
    public Map<String, Double> getNeeds() {
        return needs;
    }

    @Nullable
    public Double getNeed(@Nullable String needId) {
        return needs.get(normalizeKey(needId));
    }

    @Nonnull
    public Map<String, Double> getTraits() {
        return traits;
    }

    public boolean hasTrait(@Nullable String traitId) {
        return traits.containsKey(normalizeKey(traitId));
    }

    @Nullable
    public Double getTrait(@Nullable String traitId) {
        return traits.get(normalizeKey(traitId));
    }

    @Nonnull
    public Map<String, String> getCommandStates() {
        return commandStates;
    }

    @Nullable
    public String getCommandState(@Nullable String state) {
        return commandStates.get(normalizeKey(state));
    }

    @Nonnull
    static String normalizeKey(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Nonnull
    private static Map<String, Double> copyNormalizedDoubleMap(@Nullable Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (!key.isEmpty() && entry.getValue() != null) {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    @Nonnull
    private static Map<String, String> copyNormalizedStringMap(@Nullable Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (!key.isEmpty() && entry.getValue() != null) {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    /** Builder for dynamic attachment NPC snapshots. */
    public static final class Builder {
        private String roleId;
        private String displayName;
        private Boolean ownerPresent;
        private Boolean tamed;
        private String gender;
        private String lifeStage;
        private Double happiness;
        private Map<String, Double> needs = Map.of();
        private Map<String, Double> traits = Map.of();
        private Map<String, String> commandStates = Map.of();

        private Builder() {
        }

        @Nonnull
        public Builder roleId(@Nullable String roleId) {
            this.roleId = roleId;
            return this;
        }

        @Nonnull
        public Builder displayName(@Nullable String displayName) {
            this.displayName = displayName;
            return this;
        }

        @Nonnull
        public Builder ownerPresent(boolean ownerPresent) {
            this.ownerPresent = ownerPresent;
            return this;
        }

        @Nonnull
        public Builder tamed(boolean tamed) {
            this.tamed = tamed;
            return this;
        }

        @Nonnull
        public Builder gender(@Nullable String gender) {
            this.gender = gender;
            return this;
        }

        @Nonnull
        public Builder lifeStage(@Nullable String lifeStage) {
            this.lifeStage = lifeStage;
            return this;
        }

        @Nonnull
        public Builder happiness(@Nullable Double happiness) {
            this.happiness = happiness;
            return this;
        }

        @Nonnull
        public Builder needs(@Nullable Map<String, Double> needs) {
            this.needs = needs == null || needs.isEmpty() ? Map.of() : new HashMap<>(needs);
            return this;
        }

        @Nonnull
        public Builder need(@Nullable String needId, double value) {
            needs = mutableCopy(needs);
            needs.put(needId, value);
            return this;
        }

        @Nonnull
        public Builder traits(@Nullable Map<String, Double> traits) {
            this.traits = traits == null || traits.isEmpty() ? Map.of() : new HashMap<>(traits);
            return this;
        }

        @Nonnull
        public Builder trait(@Nullable String traitId, double value) {
            traits = mutableCopy(traits);
            traits.put(traitId, value);
            return this;
        }

        @Nonnull
        public Builder commandStates(@Nullable Map<String, String> commandStates) {
            this.commandStates = commandStates == null || commandStates.isEmpty()
                    ? Map.of()
                    : new HashMap<>(commandStates);
            return this;
        }

        @Nonnull
        public Builder commandState(@Nullable String state, @Nullable String value) {
            commandStates = mutableCopy(commandStates);
            commandStates.put(state, value);
            return this;
        }

        @Nonnull
        public DynamicAttachmentNpcSnapshot build() {
            return new DynamicAttachmentNpcSnapshot(this);
        }

        @Nonnull
        private static <T> Map<String, T> mutableCopy(@Nonnull Map<String, T> values) {
            return values.isEmpty() ? new HashMap<>() : new HashMap<>(values);
        }
    }
}
