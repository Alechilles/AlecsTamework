package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ProgressionView(@Nullable String profileId,
                              @Nonnull UUID npcUuid,
                              @Nullable String worldName,
                              @Nullable String roleId,
                              @Nullable HappinessView happiness,
                              @Nullable NeedsView needs,
                              @Nullable BreedingView breeding,
                              @Nullable LifeStageView lifeStage,
                              @Nullable TraitsView traits,
                              @Nullable AttachmentsView attachments) {
    public record HappinessView(@Nullable String configId,
                                double value,
                                double min,
                                double max,
                                long lastUpdateMs,
                                @Nonnull String source,
                                double baseSetpoint,
                                double target,
                                @Nonnull List<ModifierEntryView> modifiers) {
    }

    public record ModifierEntryView(@Nonnull String id,
                                    @Nonnull String label,
                                    double value) {
    }

    public record NeedsView(@Nullable String configId,
                            double hunger,
                            double thirst,
                            @Nullable Integer hungerPercent,
                            @Nullable Integer thirstPercent,
                            double appliedHappinessPenalty,
                            long lastUpdateMs,
                            long lastPassiveSweepMs) {
    }

    public record BreedingView(@Nullable String configId,
                               boolean enabled,
                               boolean readyFlag,
                               boolean readyNow,
                               boolean cooldownActive,
                               long cooldownUntilMs,
                               long cooldownStartedAtMs,
                               long cooldownDurationMs,
                               long cooldownRemainingMs,
                               @Nullable UUID lastPartnerUuid,
                               double trackedHappiness,
                               long lastHappinessUpdateMs,
                               @Nullable Double effectiveHappiness,
                               @Nullable Double threshold,
                               @Nullable Boolean eligible,
                               double fertilityMultiplier) {
    }

    public record LifeStageView(@Nonnull String stage,
                                boolean adult,
                                boolean growthScalingEnabled,
                                double currentScale,
                                long bornAtMs,
                                long adolescentAtMs,
                                long adultAtMs,
                                long fullyGrownAtMs,
                                double babyScale,
                                double adolescentScale,
                                double adolescentSwitchScale,
                                double adultStartScale,
                                double adultSwitchScale,
                                double adultScale,
                                @Nullable String adultRoleId,
                                @Nullable String babyRoleId,
                                @Nullable String adolescentRoleId) {
    }

    public record TraitsView(@Nullable String configId,
                             long rollSeed,
                             @Nonnull List<TraitValueView> values) {
    }

    public record TraitValueView(@Nonnull String id,
                                 double value,
                                 @Nullable String effectKey) {
    }

    public record AttachmentsView(@Nullable String configId,
                                  @Nonnull Map<String, String> storedAttachmentIds,
                                  @Nonnull Map<String, String> currentAttachmentIds) {
    }
}
