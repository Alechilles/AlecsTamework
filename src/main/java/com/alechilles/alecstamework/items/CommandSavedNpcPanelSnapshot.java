package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpoint;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCodec;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Immutable last-known companion card values decoded away from the world thread.
 *
 * <p>The decoder reads only presentation components from an exact checkpoint. It never creates
 * an entity holder or retains mutable component state. Config resolution is intentionally delayed
 * until {@link #apply(LinkedNpcEntry, String)}, which the caller runs on the owning world thread.</p>
 */
final class CommandSavedNpcPanelSnapshot {
    private static final String COMPONENTS = "Components";
    private static final com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry
            SNAPSHOT_CODECS = TameworkSnapshotCodecs.create();
    private final long observedAtMs;
    private final String roleId;
    private final Facts facts;
    private final Appearance appearance;
    private final boolean exactCheckpoint;
    private final boolean savedTalentsEditable;

    private CommandSavedNpcPanelSnapshot(long observedAtMs, String roleId, Facts facts,
                                         Appearance appearance, boolean exactCheckpoint) {
        this(observedAtMs, roleId, facts, appearance, exactCheckpoint, false);
    }

    private CommandSavedNpcPanelSnapshot(long observedAtMs, String roleId, Facts facts,
                                        Appearance appearance, boolean exactCheckpoint,
                                        boolean savedTalentsEditable) {
        this.savedTalentsEditable = savedTalentsEditable;
        this.observedAtMs = observedAtMs;
        this.roleId = trimToNull(roleId);
        this.facts = facts;
        this.appearance = appearance;
        this.exactCheckpoint = exactCheckpoint;
    }

    /** Decodes the newest valid full-state snapshot. Callers with checkpoint evidence use the overload. */
    @Nullable
    static CommandSavedNpcPanelSnapshot decode(CompanionProfileReadModel profile) {
        return decode(profile, null);
    }

    /**
     * Decodes the newest valid persisted presentation evidence.
     *
     * <p>Malformed optional evidence is ignored. A checkpoint is considered only after its
     * integrity envelope validates, then only named component documents are decoded.</p>
     */
    @Nullable
    static CommandSavedNpcPanelSnapshot decode(
            CompanionProfileReadModel profile,
            @Nullable String checkpointJson
    ) {
        if (profile == null) {
            return null;
        }
        var restoration = com.alechilles.alecstamework.companion.progression.SavedCompanionTalentSnapshot.find(profile);
        if (restoration != null) {
            // Purchases update the restoration snapshot; an older entity checkpoint must not mask them.
            var saved = fromState(restoration.fullState(), restoration.snapshot().createdAtMs());
            return new CommandSavedNpcPanelSnapshot(saved.observedAtMs, saved.roleId, saved.facts,
                    saved.appearance, false, true);
        }
        ArrayList<CommandSavedNpcPanelSnapshot> candidates = new ArrayList<>();
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            CommandSavedNpcPanelSnapshot decoded = decodeFullState(snapshot);
            if (decoded != null) {
                candidates.add(decoded);
            }
        }
        CommandSavedNpcPanelSnapshot checkpoint = decodeCheckpoint(profile, checkpointJson);
        if (checkpoint != null) {
            candidates.add(checkpoint);
        }
        CommandSavedNpcPanelSnapshot latest = candidates.stream()
                .max(Comparator.comparingLong(value -> value.observedAtMs)).orElse(null);
        if (latest == null) return null;
        return latest;
    }

    /** Applies only known saved fields and leaves unavailable legacy fields as supplied by the base entry. */
    LinkedNpcEntry apply(LinkedNpcEntry base, @Nullable String language) {
        if (base == null) {
            return null;
        }
        String effectiveRole = firstNonBlank(roleId, base.speciesId());
        Health health = facts.health != null ? facts.health : new Health(base.currentHealth(), base.maxHealth());
        Meter happiness = resolveHappiness(facts.happiness, effectiveRole, base);
        Meter needs = resolveNeeds(facts.needs, effectiveRole, base);
        Progression progression = resolveProgression(facts.leveling, facts.talents, effectiveRole, language, base);
        LinkedNpcTraitIndicator[] traits = resolveTraits(facts.traits, effectiveRole, language, base.traitIndicators());
        Cooldown breeding = facts.breeding == null ? Cooldown.from(base.breedingCooldownKnown(), base.breedingCooldownActive(), base.breedingCooldownRemainingMs(), base.breedingCooldownRatio()) : facts.breeding.cooldown();
        Cooldown harvest = resolveHarvest(facts.harvest, effectiveRole, base);
        boolean breedingEnabled = facts.breeding == null ? base.breedingEnabled() : facts.breeding.enabled;
        boolean breedingAvailable = facts.breeding == null ? base.breedingAvailable() : true;
        LinkedNpcEntry applied = new LinkedNpcEntry(
                base.npcUuid(), base.displayName(), base.gender(), health.current, health.maximum,
                happiness.current, happiness.maximum, happiness.targetPercent,
                base.happinessModifierBreakdown(), needs.current, needs.maximum,
                needs.secondaryCurrent, needs.secondaryMaximum, base.loaded(), base.hasHome(),
                base.dead(), base.captured(), base.inCoop(), base.lost(),
                base.deadRespawnRemainingMs(), base.deathCauseHint(), progression.level,
                progression.talents, traits, facts.traits != null || base.isTraitsActionVisible(),
                base.loaded() && base.isTraitsActionEnabled(), progression.talents != null || base.isTalentsActionVisible(),
                base.loaded() ? base.isTalentsActionEnabled()
                        : savedTalentsEditable && progression.talents != null && (base.dead() || base.lost()),
                base.linked(), base.active(),
                base.speciesId(), base.speciesLabel(), base.groupId(), base.groupName(),
                base.groupColorHex(), breedingEnabled, breedingAvailable, breeding.active,
                breeding.remainingMs, breeding.ratio, breeding.known, harvest.active,
                harvest.remainingMs, harvest.ratio, harvest.known, base.recallPending(),
                base.recallLostRemainingMs());
        applied = applied.withRoleSubtitle(base.roleSubtitle())
                .withPortraitIcon(resolvePortrait(effectiveRole, base.portraitIcon()))
                .withBreedingHappinessRatio(base.breedingHappinessRatio())
                .withFlightToggle(base.flightToggleAvailable(), base.flightToggleAirborne())
                .withShoulderRide(base.shoulderRideAvailable(), base.shoulderRideMounted());
        if (base.ownedActions()) {
            applied = applied.withOwnedActions();
        }
        return base.recoveryHeld() ? applied.withRecoveryHold(base.recoveryIncidentId()) : applied;
    }

    private static CommandSavedNpcPanelSnapshot decodeFullState(CompanionSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            SnapshotDecodeResult<CoopResidentStateSnapshot> state = SNAPSHOT_CODECS
                    .decode(snapshot, CoopResidentStateSnapshot.class);
            if (state instanceof SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> found) {
                return fromState(found.value(), snapshot.createdAtMs());
            }
            SnapshotDecodeResult<DeathSnapshotV2Payload> death = SNAPSHOT_CODECS
                    .decode(snapshot, DeathSnapshotV2Payload.class);
            if (death instanceof SnapshotDecodeResult.Decoded<DeathSnapshotV2Payload> found) {
                return fromState(found.value().fullState(), snapshot.createdAtMs());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Optional card presentation must not fail profile processing.
        }
        return null;
    }

    private static CommandSavedNpcPanelSnapshot fromState(CoopResidentStateSnapshot state, long observedAtMs) {
        return new CommandSavedNpcPanelSnapshot(observedAtMs, state.roleId(), Facts.from(
                state.currentHealth() == null || state.maximumHealth() == null ? null : new Health(state.currentHealth(), state.maximumHealth()),
                state.happiness(), state.needs(), state.breeding(), state.leveling(), state.traits(), state.talents(), harvest(state.alarms())),
                Appearance.from(state.attachments(), null), false);
    }

    @Nullable
    private static CommandSavedNpcPanelSnapshot decodeCheckpoint(
            CompanionProfileReadModel profile,
            @Nullable String encoded
    ) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            CompanionEntityCheckpoint checkpoint = new CompanionEntityCheckpointCodec().decode(encoded);
            if (!checkpoint.profileId().equals(profile.identity().profileId())) {
                return null;
            }
            BsonValue rawComponents = checkpoint.holder().get(COMPONENTS);
            if (rawComponents == null || !rawComponents.isDocument()) {
                return null;
            }
            BsonDocument components = rawComponents.asDocument();
            Health health = health(component(components, "EntityStats"));
            TameworkHappinessComponent happiness = component(components, "TameworkHappiness", TameworkHappinessComponent.CODEC);
            TameworkNeedsComponent needs = component(components, "TameworkNeeds", TameworkNeedsComponent.CODEC);
            TameworkBreedingComponent breeding = component(components, "TameworkBreeding", TameworkBreedingComponent.CODEC);
            TameworkLevelingComponent leveling = component(components, "TameworkLeveling", TameworkLevelingComponent.CODEC);
            TameworkTraitsComponent traits = component(components, "TameworkTraits", TameworkTraitsComponent.CODEC);
            TameworkTalentsComponent talents = component(components, "TameworkTalents", TameworkTalentsComponent.CODEC);
            TameworkAttachmentsComponent attachments = component(
                    components, "TameworkAttachments", TameworkAttachmentsComponent.CODEC);
            TameworkAlarmComponent alarms = component(components, "TameworkAlarm", TameworkAlarmComponent.CODEC);
            return new CommandSavedNpcPanelSnapshot(checkpoint.capturedAtMs(), profile.identity().roleId(),
                    Facts.from(health, happiness, needs, breeding, leveling, traits, talents, harvest(alarms)),
                    Appearance.from(attachments, component(components, "Model")), true);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static BsonDocument component(@Nullable BsonDocument components, String name) {
        BsonValue value = components == null ? null : components.get(name);
        return value != null && value.isDocument() ? value.asDocument() : null;
    }

    @Nullable
    private static <T> T component(BsonDocument components, String name, com.hypixel.hytale.codec.Codec<T> codec) {
        BsonDocument value = component(components, name);
        if (value == null) {
            return null;
        }
        try {
            return codec.decode(value, new ExtraInfo());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Health health(@Nullable BsonDocument value) {
        if (value == null) {
            return null;
        }
        try {
            EntityStatMap map = EntityStatMap.CODEC.decode(value, new ExtraInfo());
            EntityStatValue health = map.get("Health");
            return health == null ? null : new Health(health.get(), health.getMax());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Harvest harvest(@Nullable TameworkAlarmComponent alarms) {
        if (alarms == null) {
            return null;
        }
        ArrayList<Alarm> values = new ArrayList<>();
        for (TameworkAlarmComponent.AlarmEntry alarm : alarms.getAlarms()) {
            if (alarm != null && trimToNull(alarm.getName()) != null) {
                values.add(new Alarm(alarm.getName().trim(), alarm.getUntilMs(), alarm.getStartedAtMs(), alarm.getDurationMs()));
            }
        }
        return new Harvest(List.copyOf(values));
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

    private Cooldown resolveHarvest(@Nullable Harvest saved, String role, LinkedNpcEntry base) {
        if (saved == null) {
            if (!new CommandLinkedPanelCooldownSnapshotService().hasEnabledHarvestCapability(role)) {
                return Cooldown.from(base.harvestCooldownKnown(), base.harvestCooldownActive(), base.harvestCooldownRemainingMs(), base.harvestCooldownRatio());
            }
            // An exact holder without the alarm component has the service's ready semantics.
            return exactCheckpoint
                    ? new Cooldown(true, false, 0L, 1.0)
                    : new Cooldown(true, false, -1L, 0.0);
        }
        String alarmName = CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName();
        for (Alarm alarm : saved.alarms) {
            if (alarmName.equals(alarm.name)) {
                // Snapshot timestamps use a world clock that is unavailable here. Preserve that
                // there was timer evidence without inventing an elapsed duration from wall time.
                return new Cooldown(true, false, -1L, 0.0);
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

    private static <T> T first(@Nullable T preferred, @Nullable T fallback) { return preferred != null ? preferred : fallback; }
    private static int round(double value) { return Double.isFinite(value) ? Math.max(0, (int) Math.round(value)) : 0; }
    private static int percent(double value, double min, double max) { return max <= min ? 0 : Math.max(0, Math.min(100, (int) Math.round(100.0 * (clamp(value, min, max) - min) / (max - min)))); }
    private static double clamp(double value, double min, double max) { return !Double.isFinite(value) ? min : Math.max(min, Math.min(max, value)); }
    private static double cumulativeXp(TwLevelingConfig config, int level) { double total = 0.0; int cappedLevel = Math.max(1, Math.min(level, config.getLevels().getMaxLevel())); for (int currentLevel = 2; currentLevel <= cappedLevel; currentLevel++) total += config.getLevels().getBaseXp() * Math.pow(config.getLevels().getGrowthFactor(), currentLevel - 2); return Math.max(0.0, total); }
    @Nullable private static String trimToNull(@Nullable String value) { return value == null || value.isBlank() ? null : value.trim(); }
    @Nullable private static String firstNonBlank(@Nullable String first, @Nullable String second) { return trimToNull(first) != null ? trimToNull(first) : trimToNull(second); }

    /** Resolves optional saved appearance only while the caller owns the world-thread card pass. */
    private String resolvePortrait(String role, String fallback) {
        Tamework plugin = Tamework.getInstance();
        String resolved = CommandNpcPortraitResolver.resolve(
                plugin == null ? null : plugin.getItemFeatureRegistry(), role,
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

    private record Facts(@Nullable Health health, @Nullable Happiness happiness, @Nullable Needs needs, @Nullable Breeding breeding, @Nullable Leveling leveling, @Nullable Traits traits, @Nullable Talents talents, @Nullable Harvest harvest) {
        static Facts from(@Nullable Health health, @Nullable TameworkHappinessComponent happiness, @Nullable TameworkNeedsComponent needs, @Nullable TameworkBreedingComponent breeding, @Nullable TameworkLevelingComponent leveling, @Nullable TameworkTraitsComponent traits, @Nullable TameworkTalentsComponent talents, @Nullable Harvest harvest) {
            return new Facts(health, happiness == null ? null : new Happiness(happiness.getConfigId(), happiness.getValue()), needs == null ? null : new Needs(needs.getConfigId(), needs.getHunger(), needs.getThirst()), breeding == null ? null : new Breeding(breeding.isEnabled(), breeding.getCooldownUntilMs(), breeding.getCooldownStartedAtMs(), breeding.getCooldownDurationMs()), leveling == null ? null : new Leveling(leveling.getConfigId(), leveling.getLevel(), leveling.getCurrentXp(), leveling.getTotalXp()), traits == null ? null : Traits.from(traits), talents == null ? null : new Talents(talents.getConfigId(), talents.getSpentPoints()), harvest);
        }
    }
    private record Appearance(@Nullable String modelId, Map<String, String> attachments) {
        private Appearance {
            modelId = trimToNull(modelId);
            attachments = attachments == null || attachments.isEmpty() ? Map.of() : Map.copyOf(attachments);
        }

        static Appearance from(@Nullable TameworkAttachmentsComponent saved,
                               @Nullable BsonDocument model) {
            Map<String, String> attachmentIds = saved == null ? Map.of() : saved.getAttachmentIds();
            BsonDocument modelState = component(model, "Model");
            String modelId = string(modelState, "Id");
            if (attachmentIds == null || attachmentIds.isEmpty()) {
                attachmentIds = stringMap(component(modelState, "RandomAttachments"));
            }
            return new Appearance(modelId, attachmentIds);
        }

        @Nullable
        private static String string(@Nullable BsonDocument document, String key) {
            BsonValue value = document == null ? null : document.get(key);
            return value != null && value.isString() ? trimToNull(value.asString().getValue()) : null;
        }

        private static Map<String, String> stringMap(@Nullable BsonDocument document) {
            if (document == null || document.isEmpty()) {
                return Map.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
                String key = trimToNull(entry.getKey());
                BsonValue value = entry.getValue();
                String attachment = value != null && value.isString()
                        ? trimToNull(value.asString().getValue()) : null;
                if (key != null && attachment != null) {
                    values.put(key, attachment);
                }
            }
            return values.isEmpty() ? Map.of() : Map.copyOf(values);
        }
    }
    private record Health(int current, int maximum) { Health(double current, double maximum) { this(round(current), Math.max(1, round(maximum))); } }
    private record Happiness(String configId, double value) { }
    private record Needs(String configId, double hunger, double thirst) { }
    private record Breeding(boolean enabled, long untilMs, long startedAtMs, long durationMs) { Cooldown cooldown() { return untilMs == 0L ? new Cooldown(true, false, 0L, 1.0) : new Cooldown(true, false, -1L, 0.0); } }
    private record Leveling(String configId, int level, double currentXp, double totalXp) { }
    private record Talents(String configId, int spentPoints) { }
    private record Trait(String id, double value) { }
    private record Traits(String configId, List<Trait> values) { static Traits from(TameworkTraitsComponent source) { ArrayList<Trait> values = new ArrayList<>(); for (TameworkTraitsComponent.TraitValue value : source.getTraitValues()) if (value != null && trimToNull(value.getId()) != null) values.add(new Trait(value.getId().trim(), value.getValue())); return new Traits(source.getConfigId(), List.copyOf(values)); } }
    private record Alarm(String name, long untilMs, long startedAtMs, long durationMs) { }
    private record Harvest(List<Alarm> alarms) { }
    private record Cooldown(boolean known, boolean active, long remainingMs, double ratio) { static Cooldown from(boolean known, boolean active, long remainingMs, double ratio) { return new Cooldown(known, active, remainingMs, ratio); } }
    private record Meter(int current, int maximum, int secondaryCurrent, int secondaryMaximum, int targetPercent) { static Meter happiness(LinkedNpcEntry entry) { return new Meter(entry.currentHappiness(), entry.maxHappiness(), entry.currentHunger(), entry.maxHunger(), entry.targetHappinessPercent()); } static Meter happiness(double value, double maximum, int targetPercent) { return new Meter(round(value), Math.max(1, round(maximum)), 0, 0, targetPercent); } static Meter needs(LinkedNpcEntry entry) { return new Meter(entry.currentHappiness(), entry.maxHappiness(), entry.currentHunger(), entry.maxHunger(), entry.targetHappinessPercent()).withNeeds(entry.currentHunger(), entry.maxHunger(), entry.currentThirst(), entry.maxThirst()); } static Meter needs(double hunger, double hungerMax, double thirst, double thirstMax) { return new Meter(0, 0, round(hunger), Math.max(1, round(hungerMax)), 0).withNeeds(round(hunger), Math.max(1, round(hungerMax)), round(thirst), Math.max(1, round(thirstMax))); } Meter withNeeds(int hunger, int hungerMax, int thirst, int thirstMax) { return new Meter(hunger, hungerMax, thirst, thirstMax, targetPercent); } }
    private record Progression(@Nullable LinkedNpcEntry.FutureStat level, @Nullable LinkedNpcEntry.FutureStat talents) { }
}
