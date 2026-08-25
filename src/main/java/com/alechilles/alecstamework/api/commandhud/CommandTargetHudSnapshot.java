package com.alechilles.alecstamework.api.commandhud;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, detached base presentation for one command target HUD. */
public final class CommandTargetHudSnapshot {
    @Nullable
    private final UUID targetUuid;
    @Nullable
    private final String targetKey;
    @Nonnull
    private final String displayName;
    @Nullable
    private final String speciesId;
    @Nullable
    private final String speciesLabel;
    @Nullable
    private final String gender;
    @Nonnull
    private final String lifecycleStatus;
    @Nonnull
    private final Vitals vitals;
    @Nonnull
    private final Cooldowns cooldowns;
    @Nullable
    private final FoodRow favoriteFood;
    @Nonnull
    private final List<FoodRow> compatibleFoods;
    @Nonnull
    private final List<AttachmentRow> attachments;
    @Nullable
    private final TameRequirement tameRequirement;
    @Nonnull
    private final Progression progression;
    @Nonnull
    private final List<Trait> traits;
    @Nullable
    private final String ownerDisplayName;

    /** Creates a complete detached target snapshot. */
    public CommandTargetHudSnapshot(
            @Nullable UUID targetUuid,
            @Nullable String targetKey,
            @Nullable String displayName,
            @Nullable String speciesId,
            @Nullable String speciesLabel,
            @Nullable String gender,
            @Nullable String lifecycleStatus,
            @Nullable Vitals vitals,
            @Nullable Cooldowns cooldowns,
            @Nullable FoodRow favoriteFood,
            @Nullable List<FoodRow> compatibleFoods,
            @Nullable List<AttachmentRow> attachments,
            @Nullable TameRequirement tameRequirement,
            @Nullable Progression progression,
            @Nullable List<Trait> traits,
            @Nullable String ownerDisplayName
    ) {
        this.targetUuid = targetUuid;
        this.targetKey = normalize(targetKey);
        this.displayName = normalize(displayName);
        this.speciesId = normalize(speciesId);
        this.speciesLabel = normalize(speciesLabel);
        this.gender = normalize(gender);
        this.lifecycleStatus = normalize(lifecycleStatus);
        this.vitals = vitals == null ? Vitals.empty() : vitals;
        this.cooldowns = cooldowns == null ? Cooldowns.empty() : cooldowns;
        this.favoriteFood = favoriteFood;
        this.compatibleFoods = copyList(compatibleFoods);
        this.attachments = copyList(attachments);
        this.tameRequirement = tameRequirement;
        this.progression = progression == null ? Progression.empty() : progression;
        this.traits = copyList(traits);
        this.ownerDisplayName = normalize(ownerDisplayName);
    }

    /** Creates a snapshot without a separate target key, species label, or gender. */
    public CommandTargetHudSnapshot(
            @Nullable UUID targetUuid,
            @Nullable String displayName,
            @Nullable String speciesId,
            @Nullable String lifecycleStatus,
            @Nullable Vitals vitals,
            @Nullable Cooldowns cooldowns,
            @Nullable FoodRow favoriteFood,
            @Nullable List<FoodRow> compatibleFoods,
            @Nullable List<AttachmentRow> attachments,
            @Nullable TameRequirement tameRequirement,
            @Nullable Progression progression,
            @Nullable List<Trait> traits,
            @Nullable String ownerDisplayName
    ) {
        this(targetUuid, null, displayName, speciesId, null, null, lifecycleStatus,
                vitals, cooldowns, favoriteFood, compatibleFoods, attachments,
                tameRequirement, progression, traits, ownerDisplayName);
    }

    @Nullable
    public UUID targetUuid() {
        return targetUuid;
    }

    @Nullable
    public UUID targetId() {
        return targetUuid;
    }

    @Nullable
    public String targetKey() {
        return targetKey;
    }

    @Nonnull
    public String displayName() {
        return displayName;
    }

    @Nullable
    public String speciesId() {
        return speciesId;
    }

    @Nullable
    public String speciesLabel() {
        return speciesLabel;
    }

    @Nullable
    public String gender() {
        return gender;
    }

    @Nonnull
    public String lifecycleStatus() {
        return lifecycleStatus;
    }

    /** Alias for integrations that call the lifecycle field simply status. */
    @Nonnull
    public String status() {
        return lifecycleStatus;
    }

    @Nonnull
    public Vitals vitals() {
        return vitals;
    }

    @Nonnull
    public Cooldowns cooldowns() {
        return cooldowns;
    }

    @Nullable
    public FoodRow favoriteFood() {
        return favoriteFood;
    }

    @Nonnull
    public List<FoodRow> compatibleFoods() {
        return compatibleFoods;
    }

    @Nonnull
    public List<AttachmentRow> attachments() {
        return attachments;
    }

    @Nullable
    public TameRequirement tameRequirement() {
        return tameRequirement;
    }

    @Nonnull
    public Progression progression() {
        return progression;
    }

    @Nonnull
    public List<Trait> traits() {
        return traits;
    }

    @Nullable
    public String ownerDisplayName() {
        return ownerDisplayName;
    }

    /** Detached health, happiness, hunger, and thirst values. */
    public record Vitals(
            @Nullable Integer currentHealth,
            @Nullable Integer maxHealth,
            @Nullable Integer currentHappiness,
            @Nullable Integer maxHappiness,
            @Nullable Integer targetHappinessPercent,
            @Nullable Integer currentHunger,
            @Nullable Integer maxHunger,
            @Nullable Integer currentThirst,
            @Nullable Integer maxThirst
    ) {
        /** Creates vitals without a known happiness target. */
        public Vitals(
                @Nullable Integer currentHealth,
                @Nullable Integer maxHealth,
                @Nullable Integer currentHappiness,
                @Nullable Integer maxHappiness,
                @Nullable Integer currentHunger,
                @Nullable Integer maxHunger,
                @Nullable Integer currentThirst,
                @Nullable Integer maxThirst
        ) {
            this(currentHealth, maxHealth, currentHappiness, maxHappiness, null,
                    currentHunger, maxHunger, currentThirst, maxThirst);
        }

        /** Returns an absent-value set of vitals. */
        public static Vitals empty() {
            return new Vitals(null, null, null, null, null, null, null, null, null);
        }

        public Integer health() {
            return currentHealth;
        }

        public Integer happiness() {
            return currentHappiness;
        }

        public Integer happinessTargetPercent() {
            return targetHappinessPercent;
        }

        public Integer hunger() {
            return currentHunger;
        }

        public Integer thirst() {
            return currentThirst;
        }
    }

    /** Presentation state for one known or absent cooldown. */
    public record Cooldown(
            @Nullable Boolean active,
            @Nullable Long remainingMillis,
            @Nullable Double ratio,
            @Nullable Boolean known
    ) {
        public Cooldown {
            if (ratio != null && !Double.isFinite(ratio)) {
                throw new IllegalArgumentException("Cooldown ratio must be finite.");
            }
        }

        /** Creates a cooldown with primitive presentation values. */
        public Cooldown(boolean active, long remainingMillis, double ratio, boolean known) {
            this(Boolean.valueOf(active), Long.valueOf(remainingMillis),
                    Double.valueOf(ratio), Boolean.valueOf(known));
        }

        public static Cooldown empty() {
            return new Cooldown(null, null, null, null);
        }
    }

    /** Harvest and breeding cooldown presentations. */
    public record Cooldowns(
            @Nullable Cooldown harvest,
            @Nullable Cooldown breeding
    ) {
        public Cooldowns {
            harvest = harvest == null ? Cooldown.empty() : harvest;
            breeding = breeding == null ? Cooldown.empty() : breeding;
        }

        public static Cooldowns empty() {
            return new Cooldowns(null, null);
        }

        public Cooldown harvestCooldown() {
            return harvest;
        }

        public Cooldown breedingCooldown() {
            return breeding;
        }
    }

    /** One favorite or compatible food presentation row. */
    public record FoodRow(
            @Nonnull String itemId,
            @Nonnull String displayName,
            @Nullable String iconPath,
            @Nullable Double happinessDelta
    ) {
        public FoodRow {
            itemId = normalize(itemId);
            displayName = normalize(displayName);
            iconPath = normalize(iconPath);
            if (happinessDelta != null && !Double.isFinite(happinessDelta)) {
                throw new IllegalArgumentException("Food happiness delta must be finite.");
            }
        }

        public FoodRow(
                @Nonnull String itemId,
                @Nonnull String displayName,
                @Nullable String iconPath
        ) {
            this(itemId, displayName, iconPath, null);
        }
    }

    /** One dynamic attachment presentation row. */
    public record AttachmentRow(
            @Nonnull String setLabel,
            @Nonnull String valueLabel
    ) {
        public AttachmentRow {
            setLabel = normalize(setLabel);
            valueLabel = normalize(valueLabel);
        }

        @Nonnull
        public String displayLine() {
            return valueLabel.isEmpty() ? setLabel : setLabel + ": " + valueLabel;
        }
    }

    /** Tame or tranquilizer requirement presentation. */
    public record TameRequirement(
            @Nullable Boolean tranquilizerRequired,
            @Nullable Integer requiredStacks,
            @Nullable String currentStacksText
    ) {
        public TameRequirement {
            currentStacksText = normalize(currentStacksText);
        }

        public TameRequirement(
                boolean tranquilizerRequired,
                int requiredStacks,
                @Nullable String currentStacksText
        ) {
            this(Boolean.valueOf(tranquilizerRequired), Integer.valueOf(requiredStacks),
                    currentStacksText);
        }
    }

    /** Level, experience, and talent presentation. */
    public record Progression(
            @Nullable Integer level,
            @Nullable Long experience,
            @Nullable Long experienceToNextLevel,
            @Nullable Integer availableTalentPoints,
            @Nullable Integer maxLevel,
            @Nullable Boolean atMaxLevel
    ) {
        /** Creates progression without maximum-level metadata. */
        public Progression(
                @Nullable Integer level,
                @Nullable Long experience,
                @Nullable Long experienceToNextLevel,
                @Nullable Integer availableTalentPoints
        ) {
            this(level, experience, experienceToNextLevel, availableTalentPoints,
                    null, null);
        }

        public static Progression empty() {
            return new Progression(null, null, null, null, null, null);
        }

        public Integer talentPoints() {
            return availableTalentPoints;
        }

        public Integer maximumLevel() {
            return maxLevel;
        }

        public Boolean maximumLevelState() {
            return atMaxLevel;
        }
    }

    /** One detached trait indicator. */
    public record Trait(
            @Nonnull String id,
            @Nonnull String label,
            @Nullable String iconPath,
            @Nonnull String iconText,
            @Nonnull String tooltipText,
            double fillRatio,
            boolean counterClockwise,
            boolean belowDefault
    ) {
        /** Creates a trait with standard fallback presentation values. */
        public Trait(
                @Nonnull String id,
                @Nonnull String label,
                @Nullable String iconPath
        ) {
            this(id, label, iconPath, "?", null, 0.0, false, false);
        }

        public Trait {
            id = normalize(id);
            label = normalize(label);
            iconPath = normalizeNullable(iconPath);
            iconText = iconText == null || iconText.isBlank() ? "?" : iconText.trim();
            tooltipText = tooltipText == null || tooltipText.isBlank()
                    ? label : tooltipText.trim();
            fillRatio = clampRatio(fillRatio);
        }
    }

    @Nonnull
    private static <T> List<T> copyList(@Nullable List<T> source) {
        if (source == null || source.isEmpty()) return List.of();
        return List.copyOf(source);
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double clampRatio(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandTargetHudSnapshot that)) return false;
        return Objects.equals(targetUuid, that.targetUuid)
                && Objects.equals(targetKey, that.targetKey)
                && displayName.equals(that.displayName)
                && Objects.equals(speciesId, that.speciesId)
                && Objects.equals(speciesLabel, that.speciesLabel)
                && Objects.equals(gender, that.gender)
                && lifecycleStatus.equals(that.lifecycleStatus)
                && vitals.equals(that.vitals)
                && cooldowns.equals(that.cooldowns)
                && Objects.equals(favoriteFood, that.favoriteFood)
                && compatibleFoods.equals(that.compatibleFoods)
                && attachments.equals(that.attachments)
                && Objects.equals(tameRequirement, that.tameRequirement)
                && progression.equals(that.progression)
                && traits.equals(that.traits)
                && Objects.equals(ownerDisplayName, that.ownerDisplayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetUuid, targetKey, displayName, speciesId, speciesLabel,
                gender, lifecycleStatus, vitals, cooldowns, favoriteFood,
                compatibleFoods, attachments, tameRequirement, progression,
                traits, ownerDisplayName);
    }
}
