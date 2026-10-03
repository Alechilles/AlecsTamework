package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.SummaryLifeStage;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.CaptureKey;
import com.alechilles.alecstamework.items.locate.CapturedItemMetadata;
import com.alechilles.alecstamework.config.assets.TwDynamicIconConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import com.alechilles.alecstamework.npc.progression.TraitPresentationViewMapper;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Immutable last-known companion card values for an unloaded companion.
 *
 * <p>Values come from the companion index summary captured from the live body. Config resolution
 * is intentionally delayed until {@link #apply(LinkedNpcEntry, String)}, which the caller runs on
 * the owning world thread.</p>
 */
final class CommandSavedNpcPanelSnapshot {
    private final long observedAtMs;
    private final String roleId;
    private final Facts facts;
    private final Appearance appearance;
    private final StoredLocation storedLocation;

    /** Where a stored companion is: its coop block (world and block position) or its capture item key. */
    record StoredLocation(@Nullable CoopLocation coop,
                          @Nullable CaptureKey capture) { }

    /** A coop block's world and position. */
    record CoopLocation(String world, int x, int y, int z) { }

    StoredLocation storedLocation() { return storedLocation; }

    /**
     * Returns saved scalar care values for roster ordering without constructing
     * progression, trait, cooldown, location, or tooltip presentation.
     */
    @Nullable
    CareSnapshot careSnapshot(@Nullable String fallbackRole) {
        if (facts == null) return null;
        String effectiveRole = firstNonBlank(roleId, fallbackRole);
        int happiness = 0;
        int maxHappiness = 0;
        if (facts.happiness != null) {
            TwHappinessConfig config = first(TwHappinessConfig.resolveById(facts.happiness.configId),
                    TwHappinessConfig.resolveForRole(effectiveRole));
            if (config != null && config.isEnabled()) {
                maxHappiness = Math.max(1, round(config.getValues().getMax()));
                happiness = Math.max(0, Math.min(maxHappiness, round(facts.happiness.value)));
            }
        }
        int hunger = 0;
        int maxHunger = 0;
        int thirst = 0;
        int maxThirst = 0;
        if (facts.needs != null) {
            TwNeedsConfig config = first(TwNeedsConfig.resolveById(facts.needs.configId),
                    TwNeedsConfig.resolveForRole(effectiveRole));
            if (config != null && config.isEnabled()) {
                maxHunger = Math.max(1, round(config.getValues().getHungerMax()));
                maxThirst = Math.max(1, round(config.getValues().getThirstMax()));
                hunger = Math.max(0, Math.min(maxHunger, round(facts.needs.hunger)));
                thirst = Math.max(0, Math.min(maxThirst, round(facts.needs.thirst)));
            }
        }
        return new CareSnapshot(happiness, maxHappiness, hunger, maxHunger, thirst, maxThirst);
    }

    private CommandSavedNpcPanelSnapshot(CommandSavedNpcPanelSnapshot source, StoredLocation location) {
        this.observedAtMs = source == null ? 0 : source.observedAtMs;
        this.roleId = source == null ? null : source.roleId;
        this.facts = source == null ? null : source.facts;
        this.appearance = source == null ? null : source.appearance;
        this.storedLocation = location;
    }

    private CommandSavedNpcPanelSnapshot(long observedAtMs, String roleId, Facts facts,
                                         Appearance appearance) {
        this.storedLocation = null;
        this.observedAtMs = observedAtMs;
        this.roleId = trimToNull(roleId);
        this.facts = facts;
        this.appearance = appearance;
    }

    /**
     * Builds the unloaded panel from the index summary (spec 6.6) without decoding a snapshot.
     * The summary was captured from the live body, so a section it lacks is one the body did not
     * have. A COOP record also carries its coop block from the record's location, even when
     * the summary was never captured, and an ITEM record carries the key of the capture item made
     * at its generation. Returns null otherwise when the summary was never captured.
     */
    @Nullable
    static CommandSavedNpcPanelSnapshot fromSummary(CompanionRecord record) {
        if (record == null) {
            return null;
        }
        CommandSavedNpcPanelSnapshot saved = fromSummaryFacts(record);
        CompanionLocation at = record.location();
        if (at.kind() == LocationKind.ITEM) {
            return new CommandSavedNpcPanelSnapshot(saved, new StoredLocation(null,
                    CapturedItemMetadata.indexKey(record.profileId(), record.generation())));
        }
        if (at.kind() != LocationKind.COOP || at.world() == null) {
            return saved;
        }
        return new CommandSavedNpcPanelSnapshot(saved, new StoredLocation(
                new CoopLocation(at.world(), (int) at.x(), (int) at.y(), (int) at.z()), null));
    }

    @Nullable
    private static CommandSavedNpcPanelSnapshot fromSummaryFacts(CompanionRecord record) {
        CompanionSummary s = record.summary();
        if (s == null || s.observedAtMs() == 0L) {
            return null;
        }
        Health health = s.healthMax() > 0f ? new Health(s.healthCurrent(), s.healthMax()) : null;
        Happiness happiness = s.happinessConfigId() == null ? null : new Happiness(s.happinessConfigId(), s.happiness());
        Needs needs = s.needsConfigId() == null ? null : new Needs(s.needsConfigId(), s.hunger(), s.thirst());
        Breeding breeding = s.breedingPresent() ? new Breeding(s.breedingEnabled(), s.breedingCooldownUntilMs(),
                s.breedingCooldownStartedAtMs(), s.breedingCooldownDurationMs()) : null;
        Leveling leveling = s.levelingConfigId() == null ? null
                : new Leveling(s.levelingConfigId(), s.level(), s.currentXp(), s.totalXp());
        Talents talents = s.talentsConfigId() == null ? null : new Talents(s.talentsConfigId(), s.talentPointsSpent());
        ArrayList<Trait> traitValues = new ArrayList<>();
        s.traits().forEach((id, value) -> {
            if (trimToNull(id) != null) traitValues.add(new Trait(id.trim(), value));
        });
        Traits traits = s.traitsConfigId() == null && traitValues.isEmpty() ? null
                : new Traits(s.traitsConfigId(), List.copyOf(traitValues));
        Harvest harvest = new Harvest(s.harvestAlarmUntilMs() == 0L ? List.of()
                : List.of(new Alarm(CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName(),
                        s.harvestAlarmUntilMs(), s.harvestAlarmStartedAtMs(), s.harvestAlarmDurationMs())));
        return new CommandSavedNpcPanelSnapshot(s.observedAtMs(), firstNonBlank(s.roleId(), record.roleId()),
                new Facts(health, happiness, needs, breeding, leveling, traits, talents, harvest,
                        SummaryLifeStage.of(s.progression()),
                        // The saved talent page serves the companions the generic owned actions may
                        // change, except an import whose old body may still rejoin and replace its talents.
                        !record.bonded() && record.rosterId() == null && !LegacyBodyResolution.awaitsItsBody(record)
                                ? record.roleId() : null),
                new Appearance(null, Map.of(), s.iconId()));
    }

    /** Applies only known saved fields and leaves unavailable legacy fields as supplied by the base entry. */
    LinkedNpcEntry apply(LinkedNpcEntry base, @Nullable String language) {
        return apply(base, language, BreedingTimeService.resolveCurrentGameSecondsPerRealSecond(null));
    }

    LinkedNpcEntry apply(LinkedNpcEntry base, @Nullable String language, double gameRate) {
        if (base == null) {
            return null;
        }
        if (facts == null) return base;
        String effectiveRole = firstNonBlank(roleId, base.speciesId());
        Health health = facts.health != null ? facts.health : new Health(base.currentHealth(), base.maxHealth());
        Meter happiness = resolveHappiness(facts.happiness, effectiveRole, base);
        Meter needs = resolveNeeds(facts.needs, effectiveRole, base);
        Progression progression = resolveProgression(facts.leveling, facts.talents, effectiveRole, language, base);
        LinkedNpcTraitIndicator[] traits = resolveTraits(facts.traits, effectiveRole, language, base.traitIndicators());
        boolean juvenile = isJuvenileLifeStage(facts.lifeStage);
        Cooldown breeding = juvenile ? new Cooldown(false, false, 0L, 0.0)
                : facts.breeding == null
                ? Cooldown.from(base.breedingCooldownKnown(), base.breedingCooldownActive(), base.breedingCooldownRemainingMs(), base.breedingCooldownRatio())
                : facts.breeding.cooldown(facts.lifeStage, base.captured(), gameRate);
        Cooldown harvest = resolveHarvest(facts.harvest, facts.lifeStage, base.captured(), effectiveRole, base, gameRate);
        boolean breedingEnabled = !juvenile && (facts.breeding == null ? base.breedingEnabled() : facts.breeding.enabled);
        boolean breedingAvailable = !juvenile && (facts.breeding == null ? base.breedingAvailable() : true);
        LinkedNpcEntry applied = new LinkedNpcEntry(
                base.npcUuid(), base.displayName(), base.gender(), health.current, health.maximum,
                happiness.current, happiness.maximum, happiness.targetPercent,
                base.happinessModifierBreakdown(), needs.current, needs.maximum,
                needs.secondaryCurrent, needs.secondaryMaximum, base.loaded(), base.hasHome(),
                base.dead(), base.captured(), base.inCoop(), base.lost(),
                base.deadRespawnRemainingMs(), base.deathCauseHint(), progression.level,
                progression.talents, traits, facts.traits != null || base.isTraitsActionVisible(),
                base.loaded() && base.isTraitsActionEnabled(), progression.talents != null || base.isTalentsActionVisible(),
                base.loaded() ? base.isTalentsActionEnabled() : savedTalentsEditable(base),
                base.linked(), base.active(),
                base.speciesId(), base.speciesLabel(), base.groupId(), base.groupName(),
                base.groupColorHex(), breedingEnabled, breedingAvailable, breeding.active,
                breeding.remainingMs, breeding.ratio, breeding.known, harvest.active,
                harvest.remainingMs, harvest.ratio, harvest.known, base.recallPending(),
                base.recallLostRemainingMs());
        applied = applied.withRoleSubtitle(base.roleSubtitle())
                .withPortraitIcon(resolvePortrait(effectiveRole, base.portraitIcon()))
                .withBreedingHappinessRatio(juvenile ? -1.0 : base.breedingHappinessRatio())
                .withFlightToggle(base.flightToggleAvailable(), base.flightToggleAirborne())
                .withShoulderRide(base.shoulderRideAvailable(), base.shoulderRideMounted())
                .withTraitValues(traitValues(facts.traits, effectiveRole))
                .withDeadRespawnTotalMs(base.deadRespawnTotalMs());
        if (base.ownedActions()) {
            applied = applied.withOwnedActions();
        }
        applied = applied.withAnimalLifecycle(AnimalProgressionService.presentation(
                facts.lifeStage, effectiveRole, base.captured() || base.dead()));
        return base.recoveryHeld() ? applied.withRecoveryHold(base.recoveryIncidentId()) : applied;
    }

    /**
     * A dead or lost companion's points can be spent in its stored snapshot
     * ({@link CommandSavedTalentPageService}); whether that snapshot exists is checked on open.
     */
    private boolean savedTalentsEditable(LinkedNpcEntry base) {
        if (facts.editableTalentsRole == null || facts.leveling == null || !(base.dead() || base.lost())
                || !CompanionProgressionSettings.isTalentsEnabled() || !CompanionProgressionSettings.isLevelingEnabled()) {
            return false;
        }
        // The record role's tree, as the page and the stored change use it.
        TwTalentConfig tree = TwTalentConfig.resolveForRole(facts.editableTalentsRole);
        return tree != null && tree.isEnabled();
    }

    private static boolean isJuvenileLifeStage(@Nullable TameworkLifeStageComponent lifeStage) {
        if (lifeStage == null || lifeStage.getStage() == null) {
            return false;
        }
        String stage = lifeStage.getStage();
        boolean juvenile = CompanionLifeStageService.STAGE_BABY.equalsIgnoreCase(stage)
                || CompanionLifeStageService.STAGE_ADOLESCENT.equalsIgnoreCase(stage);
        if (!juvenile || !lifeStage.isGrowthScalingEnabled()
                || !lifeStage.isJuvenileClockInitialized()
                || lifeStage.getAdultAtMs() == 0L) {
            return juvenile;
        }
        long settled = lifeStage.getActiveProgressMs();
        long active = AnimalProgressionService.activeTimeMs(lifeStage);
        long pending = active >= settled ? active - settled : 0L;
        long lifeNow = BreedingTimeService.saturatingAdd(lifeStage.getLifecycleNowMs(), pending);
        return lifeNow < lifeStage.getAdultAtMs();
    }

    private Meter resolveHappiness(@Nullable Happiness saved, String role, LinkedNpcEntry base) {
        if (saved == null) {
            return Meter.happiness(base);
        }
        TwHappinessConfig config = first(TwHappinessConfig.resolveById(saved.configId), TwHappinessConfig.resolveForRole(role));
        if (config == null || !config.isEnabled()) {
            return Meter.happiness(base);
        }
        double min = config.getValues().getMin();
        double max = config.getValues().getMax();
        return Meter.happiness(saved.value, max, percent(config.getEquilibrium().getBaseSetpoint(), min, max));
    }

    private Meter resolveNeeds(@Nullable Needs saved, String role, LinkedNpcEntry base) {
        if (saved == null) {
            return Meter.needs(base);
        }
        TwNeedsConfig config = first(TwNeedsConfig.resolveById(saved.configId), TwNeedsConfig.resolveForRole(role));
        if (config == null || !config.isEnabled()) {
            return Meter.needs(base);
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        return Meter.needs(saved.hunger, values.getHungerMax(), saved.thirst, values.getThirstMax());
    }

    private Cooldown resolveHarvest(Harvest saved,
                                    @Nullable TameworkLifeStageComponent lifeStage,
                                    boolean captured,
                                    String role,
                                    LinkedNpcEntry base, double gameRate) {
        String alarmName = CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName();
        for (Alarm alarm : saved.alarms) {
            if (alarmName.equals(alarm.name)) {
                return cooldown(alarm.untilMs, alarm.startedAtMs, alarm.durationMs, lifeStage, captured, gameRate);
            }
        }
        return new CommandLinkedPanelCooldownSnapshotService().hasEnabledHarvestCapability(role)
                ? new Cooldown(true, false, 0L, 1.0)
                : Cooldown.from(base.harvestCooldownKnown(), base.harvestCooldownActive(), base.harvestCooldownRemainingMs(), base.harvestCooldownRatio());
    }

    private Progression resolveProgression(@Nullable Leveling leveling, @Nullable Talents talents, String role, String language, LinkedNpcEntry base) {
        LinkedNpcEntry.FutureStat level = base.futureStatA();
        LinkedNpcEntry.FutureStat talent = base.futureStatB();
        if (leveling != null) {
            int maxLevel = 0;
            TwLevelingConfig config = first(
                    TwLevelingConfig.resolveById(leveling.configId),
                    TwLevelingConfig.resolveForRole(role));
            if (config != null && config.isEnabled()) {
                maxLevel = config.getLevels().getMaxLevel();
            }
            if (maxLevel > 0) {
                int savedLevel = Math.max(1, Math.min(leveling.level, maxLevel));
                boolean atMaxLevel = savedLevel >= maxLevel;
                double levelStartXp = cumulativeXp(config, savedLevel);
                double nextLevelXp = atMaxLevel ? levelStartXp : cumulativeXp(config, savedLevel + 1);
                level = new CommandLinkedPanelProgressionPresentationService().buildLevelFutureStat(
                        new CompanionLevelingService.LevelingSnapshot(
                                config.getId(), savedLevel, leveling.currentXp, leveling.totalXp,
                                levelStartXp, nextLevelXp, maxLevel, atMaxLevel),
                        language,
                        null);
            }
        }
        if (talents != null && leveling != null) {
            TwTalentConfig config = first(TwTalentConfig.resolveById(talents.configId), TwTalentConfig.resolveForRole(role));
            if (config != null && config.isEnabled()) {
                int earned = CompanionLevelingService.resolveEarnedTalentPoints(leveling.level, leveling.configId);
                talent = new LinkedNpcEntry.FutureStat(LocalizedText.resolve(language, "tamework.ui.linkedPanel.futureStat.talentPoints"), Math.max(0, earned - talents.spentPoints), Math.max(1, earned));
            }
        }
        return new Progression(level, talent);
    }

    private LinkedNpcTraitIndicator[] resolveTraits(@Nullable Traits saved, String role, String language, LinkedNpcTraitIndicator[] fallback) {
        if (saved == null) {
            return fallback;
        }
        TwTraitConfig config = first(TwTraitConfig.resolveById(saved.configId), TwTraitConfig.resolveForRole(role));
        if (config == null) {
            return fallback;
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (Trait trait : saved.values) {
            values.putIfAbsent(trait.id.toLowerCase(java.util.Locale.ROOT), trait.value);
        }
        return new CommandLinkedPanelProgressionPresentationService().buildSavedTraitIndicators(
                config, values, role, language);
    }

    @Nullable
    private static ProgressionView.TraitsView traitValues(
            @Nullable Traits saved,
            @Nullable String roleId
    ) {
        if (saved == null) return null;
        TwTraitConfig config = saved.configId == null ? null
                : TwTraitConfig.resolveById(saved.configId);
        if (config == null && roleId != null && !roleId.isBlank()) {
            config = TwTraitConfig.resolveForRole(roleId);
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (Trait value : saved.values) {
            if (value == null || value.id == null || value.id.isBlank()
                    || !Double.isFinite(value.value)) continue;
            values.putIfAbsent(value.id, value.value);
        }
        return TraitPresentationViewMapper.map(saved.configId, 0L, values, config);
    }

    private static <T> T first(@Nullable T preferred, @Nullable T fallback) { return preferred != null ? preferred : fallback; }
    private static int round(double value) { return Double.isFinite(value) ? Math.max(0, (int) Math.round(value)) : 0; }
    private static int percent(double value, double min, double max) { return max <= min ? 0 : Math.max(0, Math.min(100, (int) Math.round(100.0 * (clamp(value, min, max) - min) / (max - min)))); }
    private static double clamp(double value, double min, double max) { return !Double.isFinite(value) ? min : Math.max(min, Math.min(max, value)); }
    static double cumulativeXp(TwLevelingConfig config, int level) { double total = 0.0; int cappedLevel = Math.max(1, Math.min(level, config.getLevels().getMaxLevel())); for (int currentLevel = 2; currentLevel <= cappedLevel; currentLevel++) total += config.getLevels().getBaseXp() * Math.pow(config.getLevels().getGrowthFactor(), currentLevel - 2); return Math.max(0.0, total); }
    @Nullable private static String trimToNull(@Nullable String value) { return value == null || value.isBlank() ? null : value.trim(); }
    @Nullable private static String firstNonBlank(@Nullable String first, @Nullable String second) { return trimToNull(first) != null ? trimToNull(first) : trimToNull(second); }

    /** Resolves optional saved appearance only while the caller owns the world-thread card pass. */
    private String resolvePortrait(String role, String fallback) {
        if (appearance != null && appearance.icon != null) {
            return appearance.icon;
        }
        String resolved = TwDynamicIconConfig.resolveIcon(
                role,
                appearance == null ? null : appearance.attachments);
        if (trimToNull(resolved) != null) {
            return resolved;
        }
        String modelId = appearance == null ? null : appearance.modelId;
        if (trimToNull(modelId) == null) {
            return fallback;
        }
        try {
            ModelAsset asset = ModelAsset.getAssetMap() == null
                    ? null : ModelAsset.getAssetMap().getAsset(modelId);
            String icon = asset == null ? null : asset.getIcon();
            return trimToNull(icon) != null ? icon : fallback;
        } catch (RuntimeException | LinkageError ignored) {
            return fallback;
        }
    }

    private record Facts(@Nullable Health health, @Nullable Happiness happiness, @Nullable Needs needs, @Nullable Breeding breeding, @Nullable Leveling leveling, @Nullable Traits traits, @Nullable Talents talents, Harvest harvest, @Nullable TameworkLifeStageComponent lifeStage, @Nullable String editableTalentsRole) { }
    /** {@code icon} is a portrait already resolved from the live body (summary path); it wins when set. */
    private record Appearance(@Nullable String modelId, Map<String, String> attachments, @Nullable String icon) {
        private Appearance {
            modelId = trimToNull(modelId);
            icon = trimToNull(icon);
            attachments = attachments == null || attachments.isEmpty() ? Map.of() : Map.copyOf(attachments);
        }
    }
    private record Health(int current, int maximum) { Health(double current, double maximum) { this(round(current), Math.max(1, round(maximum))); } }
    record CareSnapshot(int happiness, int maxHappiness, int hunger, int maxHunger,
                        int thirst, int maxThirst) { }
    private record Happiness(String configId, double value) { }
    private record Needs(String configId, double hunger, double thirst) { }
    private static Cooldown cooldown(long untilMs,
                                     long startedAtMs,
                                     long durationMs,
                                     @Nullable TameworkLifeStageComponent lifeStage,
                                     boolean captured, double gameRate) {
        if (untilMs == 0L) {
            return new Cooldown(true, false, 0L, 1.0);
        }
        if (!Double.isFinite(gameRate) || gameRate <= 0.0) {
            return new Cooldown(true, false, -1L, 0.0);
        }
        double rate = gameRate;
        Long nowMs = savedProgressionTimeMs(lifeStage, captured, rate);
        if (nowMs == null) {
            return new Cooldown(true, false, -1L, 0.0);
        }
        if (!BreedingTimeService.isDeadlineActive(untilMs, nowMs)) {
            return new Cooldown(true, false, 0L, 1.0);
        }
        long remainingGameMs = BreedingTimeService.remainingDurationMs(untilMs, nowMs);
        long remainingRealMs = Math.max(0L, Math.round(remainingGameMs / rate));
        long totalGameMs = Math.max(0L, durationMs);
        if (totalGameMs <= 0L && startedAtMs != 0L && untilMs > startedAtMs) {
            totalGameMs = BreedingTimeService.saturatingSubtract(untilMs, startedAtMs);
        }
        double ratio = totalGameMs <= 0L ? 0.0
                : clamp(1.0 - ((double) remainingGameMs / (double) totalGameMs), 0.0, 1.0);
        return new Cooldown(true, true, remainingRealMs, ratio);
    }

    /**
     * Rebuilds the live virtual world clock from persisted progression evidence without reading a world.
     * Captured snapshots have no pending eligible runtime, while unloaded companions accrue it lazily.
     */
    @Nullable
    private static Long savedProgressionTimeMs(@Nullable TameworkLifeStageComponent lifeStage,
                                               boolean captured, double rate) {
        if (lifeStage == null || !lifeStage.isProgressionInitialized()
                || lifeStage.getLastProgressionWorldMs() == 0L) {
            return null;
        }
        long settled = lifeStage.getActiveProgressMs();
        long active = captured ? settled : AnimalProgressionService.activeTimeMs(lifeStage);
        long pending = active >= settled ? active - settled : 0L;
        long scaled = pending <= 0L || !Double.isFinite(rate) || rate <= 0.0 ? 0L
                : (long) Math.min(Long.MAX_VALUE, pending * rate);
        return BreedingTimeService.saturatingAdd(lifeStage.getLastProgressionWorldMs(), scaled);
    }

    private record Breeding(boolean enabled, long untilMs, long startedAtMs, long durationMs) {
        Cooldown cooldown(@Nullable TameworkLifeStageComponent lifeStage, boolean captured, double gameRate) {
            return CommandSavedNpcPanelSnapshot.cooldown(untilMs, startedAtMs, durationMs, lifeStage, captured, gameRate);
        }
    }
    private record Leveling(String configId, int level, double currentXp, double totalXp) { }
    private record Talents(String configId, int spentPoints) { }
    private record Trait(String id, double value) { }
    private record Traits(String configId, List<Trait> values) { }
    private record Alarm(String name, long untilMs, long startedAtMs, long durationMs) { }
    private record Harvest(List<Alarm> alarms) { }
    private record Cooldown(boolean known, boolean active, long remainingMs, double ratio) { static Cooldown from(boolean known, boolean active, long remainingMs, double ratio) { return new Cooldown(known, active, remainingMs, ratio); } }
    private record Meter(int current, int maximum, int secondaryCurrent, int secondaryMaximum, int targetPercent) { static Meter happiness(LinkedNpcEntry entry) { return new Meter(entry.currentHappiness(), entry.maxHappiness(), entry.currentHunger(), entry.maxHunger(), entry.targetHappinessPercent()); } static Meter happiness(double value, double maximum, int targetPercent) { return new Meter(round(value), Math.max(1, round(maximum)), 0, 0, targetPercent); } static Meter needs(LinkedNpcEntry entry) { return new Meter(entry.currentHappiness(), entry.maxHappiness(), entry.currentHunger(), entry.maxHunger(), entry.targetHappinessPercent()).withNeeds(entry.currentHunger(), entry.maxHunger(), entry.currentThirst(), entry.maxThirst()); } static Meter needs(double hunger, double hungerMax, double thirst, double thirstMax) { return new Meter(0, 0, round(hunger), Math.max(1, round(hungerMax)), 0).withNeeds(round(hunger), Math.max(1, round(hungerMax)), round(thirst), Math.max(1, round(thirstMax))); } Meter withNeeds(int hunger, int hungerMax, int thirst, int thirstMax) { return new Meter(hunger, hungerMax, thirst, thirstMax, targetPercent); } }
    private record Progression(@Nullable LinkedNpcEntry.FutureStat level, @Nullable LinkedNpcEntry.FutureStat talents) { }
}
