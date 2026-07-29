package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns companion XP, level progression, and level-based stat growth resolution.
 */
public final class CompanionLevelingService {
    private static final double EPSILON = 0.000001;

    private CompanionLevelingService() {
    }

    @Nullable
    public static TameworkLevelingComponent ensureLevelingComponent(@Nullable Ref<EntityStore> npcRef,
                                                                    @Nullable Store<EntityStore> store) {
        return ensureLevelingComponent(npcRef, store, null);
    }

    @Nullable
    public static TameworkLevelingComponent ensureLevelingComponent(@Nullable Ref<EntityStore> npcRef,
                                                                    @Nullable Store<EntityStore> store,
                                                                    @Nullable String roleIdHint) {
        return ensureLevelingComponent(npcRef, store, null, roleIdHint);
    }

    @Nullable
    public static TameworkLevelingComponent ensureLevelingComponent(@Nullable Ref<EntityStore> npcRef,
                                                                    @Nullable Store<EntityStore> store,
                                                                    @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                                    @Nullable String roleIdHint) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return null;
        }
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return null;
        }
        String roleId = roleIdHint;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        TwLevelingConfig config = TwLevelingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TameworkLevelingComponent existing = store.getComponent(npcRef, type);
        if (existing == null) {
            TameworkLevelingComponent created = new TameworkLevelingComponent(config.getId(), 1, 0.0, 0.0);
            putComponent(npcRef, store, commandBuffer, type, created);
            return created;
        }
        TameworkLevelingComponent normalized = normalizeComponent(existing, config);
        if (hasMeaningfulChange(existing, normalized) || configIdChanged(existing, normalized)) {
            putComponent(npcRef, store, commandBuffer, type, normalized);
        }
        return normalized;
    }

    @Nonnull
    public static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      double amount) {
        return awardXp(npcRef, store, null, null, CompanionXpSource.CUSTOM, amount);
    }

    @Nonnull
    public static AwardResult awardFeedXp(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store) {
        return awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.FEED, CompanionXpSource.FEED);
    }

    @Nonnull
    public static AwardResult awardHarvestXp(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        return awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.HARVEST, CompanionXpSource.HARVEST);
    }

    @Nonnull
    public static String describeHarvestXpReadiness(@Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store) {
        return describeSimpleSourceXpReadiness(npcRef, store, SimpleXpSourceType.HARVEST);
    }

    @Nonnull
    public static AwardResult awardBreedingXp(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store) {
        return awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.BREEDING, CompanionXpSource.BREEDING);
    }

    @Nonnull
    public static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable String roleIdHint,
                                      double amount) {
        return awardXp(npcRef, store, null, roleIdHint, CompanionXpSource.CUSTOM, amount);
    }

    @Nonnull
    public static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable CommandBuffer<EntityStore> commandBuffer,
                                      @Nullable String roleIdHint,
                                      double amount) {
        return awardXp(npcRef, store, commandBuffer, roleIdHint, CompanionXpSource.CUSTOM, amount);
    }

    @Nonnull
    public static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable CommandBuffer<EntityStore> commandBuffer,
                                      @Nullable String roleIdHint,
                                      @Nonnull CompanionXpSource source,
                                      double amount) {
        return awardXp(npcRef, store, commandBuffer, roleIdHint, source, amount, null);
    }

    @Nonnull
    private static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store,
                                       @Nullable CommandBuffer<EntityStore> commandBuffer,
                                       @Nullable String roleIdHint,
                                       @Nonnull CompanionXpSource source,
                                       double amount,
                                       @Nullable Long feedXpAwardedAtMs) {
        if (npcRef == null || !npcRef.isValid() || store == null || !Double.isFinite(amount) || amount <= 0.0) {
            return AwardResult.notApplied();
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return AwardResult.notApplied();
        }
        if (!isXpEligibleCompanion(npcRef, store)) {
            return AwardResult.notApplied();
        }
        String roleId = roleIdHint;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (roleId == null || roleId.isBlank()) {
            return AwardResult.notApplied();
        }
        TwLevelingConfig config = TwLevelingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return AwardResult.notApplied();
        }
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return AwardResult.notApplied();
        }
        TameworkLevelingComponent component = ensureLevelingComponent(npcRef, store, commandBuffer, roleId);
        if (component == null) {
            return AwardResult.notApplied();
        }
        int previousLevel = component.getLevel();
        double previousTotalXp = component.getTotalXp();
        double previousCurrentXp = component.getCurrentXp();
        double nextTotalXp = previousTotalXp + amount;
        long nextFeedXpAwardedAtMs = feedXpAwardedAtMs != null
                ? Math.max(0L, feedXpAwardedAtMs)
                : component.getLastFeedXpAwardedAtMs();
        TameworkLevelingComponent updated = normalizeComponent(
                new TameworkLevelingComponent(
                        component.getConfigId(),
                        component.getLevel(),
                        component.getCurrentXp(),
                        nextTotalXp,
                        nextFeedXpAwardedAtMs,
                        component.getSummonedActiveSeconds(),
                        component.getSummonedWindowAwardedXp(),
                        component.getSummonedWindowStartedAtMs(),
                        component.getSummonedLastSampleAtMs()
                ),
                config
        );
        if (!hasMeaningfulChange(component, updated)) {
            return AwardResult.notApplied();
        }
        putComponent(npcRef, store, commandBuffer, type, updated);
        if (updated.getLevel() != previousLevel) {
            applyTraitModifiers(npcRef, store, commandBuffer);
        }
        emitXpAwardedEvent(npcRef, store, config, roleId, source, amount, previousLevel, previousTotalXp, previousCurrentXp, updated);
        return new AwardResult(true, amount, previousLevel, updated.getLevel(), updated.getTotalXp());
    }

    @Nullable
    public static LevelingSnapshot resolveSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store) {
        return resolveSnapshot(npcRef, store, null);
    }

    @Nullable
    public static LevelingSnapshot resolveSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nullable String roleIdHint) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return null;
        }
        String roleId = roleIdHint;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        TameworkLevelingComponent component = type != null ? store.getComponent(npcRef, type) : null;
        TwLevelingConfig config = resolveConfig(component, roleId);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TameworkLevelingComponent normalized = component != null
                ? normalizeComponent(component, config)
                : new TameworkLevelingComponent(config.getId(), 1, 0.0, 0.0);
        int level = normalized.getLevel();
        double totalXp = normalized.getTotalXp();
        double currentLevelStartXp = resolveCumulativeXpForLevel(config, level);
        boolean atMaxLevel = level >= config.getLevels().getMaxLevel();
        double nextLevelTotalXp = atMaxLevel
                ? currentLevelStartXp
                : resolveCumulativeXpForLevel(config, level + 1);
        return new LevelingSnapshot(
                config.getId(),
                level,
                normalized.getCurrentXp(),
                totalXp,
                currentLevelStartXp,
                nextLevelTotalXp,
                config.getLevels().getMaxLevel(),
                atMaxLevel
        );
    }

    public static double resolveLevelGrowthMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable Store<EntityStore> store,
                                                      @Nullable String effectKey,
                                                      double defaultMultiplier) {
        if (npcRef == null || !npcRef.isValid() || store == null || effectKey == null || effectKey.isBlank()) {
            return defaultMultiplier;
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return defaultMultiplier;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return defaultMultiplier;
        }
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        TameworkLevelingComponent component = type != null ? store.getComponent(npcRef, type) : null;
        TwLevelingConfig config = resolveConfig(component, roleId);
        if (config == null || !config.isEnabled()) {
            return defaultMultiplier;
        }
        int level = component != null ? normalizeComponent(component, config).getLevel() : 1;
        double multiplier = defaultMultiplier;
        boolean matched = false;
        int levelOffset = Math.max(0, level - 1);
        for (TwLevelingConfig.GrowthEffect effect : config.getStatGrowth().getEffects()) {
            if (effect == null || effect.getEffectKey() == null || !effect.getEffectKey().equalsIgnoreCase(effectKey)) {
                continue;
            }
            matched = true;
            multiplier *= Math.max(0.0, 1.0 + (effect.getPerLevel() * levelOffset));
        }
        return matched ? multiplier : defaultMultiplier;
    }

    public static int resolveAvailableTalentPointsForLevel(@Nullable Ref<EntityStore> npcRef,
                                                           @Nullable Store<EntityStore> store) {
        LevelingSnapshot snapshot = resolveSnapshot(npcRef, store);
        if (snapshot == null) {
            return 0;
        }
        return resolveEarnedTalentPoints(snapshot.level(), snapshot.configId());
    }

    public static int resolveEarnedTalentPoints(int level, @Nullable String levelingConfigId) {
        if (levelingConfigId == null || levelingConfigId.isBlank()) {
            return Math.max(0, level - 1);
        }
        TwLevelingConfig config = TwLevelingConfig.resolveById(levelingConfigId);
        if (config == null) {
            return Math.max(0, level - 1);
        }
        return Math.max(0, level - 1) * config.getTalentPoints().getPointsPerLevel();
    }

    @Nonnull
    public static SetLevelResult setLevel(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store,
                                          int requestedLevel) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return SetLevelResult.notApplied("missing NPC or store");
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return SetLevelResult.notApplied("leveling system is disabled");
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return SetLevelResult.notApplied("target NPC has no role id");
        }
        TwLevelingConfig config = TwLevelingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return SetLevelResult.notApplied("no enabled leveling config for role " + roleId);
        }
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return SetLevelResult.notApplied("leveling component is not available");
        }

        int maxLevel = config.getLevels().getMaxLevel();
        int appliedLevel = Math.max(1, Math.min(requestedLevel, maxLevel));
        double totalXp = resolveCumulativeXpForLevel(config, appliedLevel);
        TameworkLevelingComponent previous = store.getComponent(npcRef, type);
        int previousLevel = previous != null ? normalizeComponent(previous, config).getLevel() : 1;
        TameworkLevelingComponent updated = new TameworkLevelingComponent(
                config.getId(),
                appliedLevel,
                0.0,
                totalXp,
                previous != null ? previous.getLastFeedXpAwardedAtMs() : 0L,
                previous != null ? previous.getSummonedActiveSeconds() : 0.0,
                previous != null ? previous.getSummonedWindowAwardedXp() : 0.0,
                previous != null ? previous.getSummonedWindowStartedAtMs() : 0L,
                previous != null ? previous.getSummonedLastSampleAtMs() : 0L
        );

        store.putComponent(npcRef, type, updated);
        applyTraitModifiers(npcRef, store, null);
        return new SetLevelResult(true, null, previousLevel, appliedLevel, maxLevel, totalXp);
    }

    private static boolean isXpEligibleCompanion(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        return resolveXpEligibility(npcRef, store).eligible();
    }

    @Nullable
    private static CompanionXpAwardedEvent buildXpAwardedEvent(@Nonnull Ref<EntityStore> npcRef,
                                                               @Nonnull Store<EntityStore> store,
                                                               @Nonnull TwLevelingConfig config,
                                                               @Nullable String roleId,
                                                               @Nonnull CompanionXpSource source,
                                                               double awardedXp,
                                                               int previousLevel,
                                                               double previousTotalXp,
                                                               double previousCurrentXp,
                                                               @Nonnull TameworkLevelingComponent updated,
                                                               long nowMs) {
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        if (npcUuid == null) {
            return null;
        }
        CreditContext creditContext = resolveCreditContext(npcRef, store);
        int maxLevel = config.getLevels().getMaxLevel();
        boolean atMaxLevel = updated.getLevel() >= maxLevel;
        double nextLevelXp = atMaxLevel ? 0.0 : nextLevelDeltaXp(config, updated.getLevel());
        return new CompanionXpAwardedEvent(
                npcUuid,
                creditContext.ownerUuid(),
                creditContext.toolIds(),
                roleId,
                updated.getConfigId(),
                source,
                awardedXp,
                previousLevel,
                updated.getLevel(),
                previousTotalXp,
                updated.getTotalXp(),
                previousCurrentXp,
                updated.getCurrentXp(),
                nextLevelXp,
                maxLevel,
                atMaxLevel,
                updated.getLevel() != previousLevel,
                nowMs,
                nowMs
        );
    }

    private static void emitXpAwardedEvent(@Nonnull Ref<EntityStore> npcRef,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull TwLevelingConfig config,
                                           @Nullable String roleId,
                                           @Nonnull CompanionXpSource source,
                                           double awardedXp,
                                           int previousLevel,
                                           double previousTotalXp,
                                           double previousCurrentXp,
                                           @Nonnull TameworkLevelingComponent updated) {
        Tamework instance = Tamework.getInstance();
        TameworkEventBus eventBus = instance != null ? instance.getApiEventBus() : null;
        if (eventBus == null) {
            return;
        }
        CompanionXpAwardedEvent event = buildXpAwardedEvent(
                npcRef,
                store,
                config,
                roleId,
                source,
                awardedXp,
                previousLevel,
                previousTotalXp,
                previousCurrentXp,
                updated,
                System.currentTimeMillis()
        );
        if (event != null) {
            eventBus.emitCompanionXpAwarded(event);
        }
    }

    @Nullable
    private static UUID resolveNpcUuid(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    @Nonnull
    private static CreditContext resolveCreditContext(@Nonnull Ref<EntityStore> npcRef,
                                                      @Nonnull Store<EntityStore> store) {
        UUID ownerUuid = null;
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType != null ? store.getComponent(npcRef, linksType) : null;
        if (links != null) {
            ownerUuid = links.getOwnerId();
            addToolIds(toolIds, links.getToolIds());
        }
        if (ownerUuid == null) {
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
            TameworkOwnerComponent owner = ownerType != null ? store.getComponent(npcRef, ownerType) : null;
            ownerUuid = owner != null ? owner.getOwnerId() : null;
        }
        return new CreditContext(ownerUuid, toolIds);
    }

    private static void addToolIds(@Nonnull LinkedHashSet<String> target, @Nullable String[] values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            target.add(value.trim());
        }
    }

    @Nullable
    private static TwLevelingConfig resolveConfig(@Nullable TameworkLevelingComponent component,
                                                  @Nullable String roleId) {
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwLevelingConfig byId = TwLevelingConfig.resolveById(component.getConfigId());
            if (byId != null) {
                return byId;
            }
        }
        return roleId == null || roleId.isBlank() ? null : TwLevelingConfig.resolveForRole(roleId);
    }

    @Nonnull
    private static TameworkLevelingComponent normalizeComponent(@Nonnull TameworkLevelingComponent component,
                                                                @Nonnull TwLevelingConfig config) {
        double totalXp = sanitizeXp(component.getTotalXp());
        int resolvedLevel = resolveLevelFromTotalXp(config, totalXp);
        double currentXp = Math.max(0.0, totalXp - resolveCumulativeXpForLevel(config, resolvedLevel));
        return new TameworkLevelingComponent(
                config.getId(),
                resolvedLevel,
                currentXp,
                totalXp,
                component.getLastFeedXpAwardedAtMs(),
                component.getSummonedActiveSeconds(),
                component.getSummonedWindowAwardedXp(),
                component.getSummonedWindowStartedAtMs(),
                component.getSummonedLastSampleAtMs()
        );
    }

    private static boolean hasMeaningfulChange(@Nonnull TameworkLevelingComponent left,
                                               @Nonnull TameworkLevelingComponent right) {
        return left.getLevel() != right.getLevel()
                || Math.abs(left.getCurrentXp() - right.getCurrentXp()) > EPSILON
                || Math.abs(left.getTotalXp() - right.getTotalXp()) > EPSILON
                || left.getLastFeedXpAwardedAtMs() != right.getLastFeedXpAwardedAtMs()
                || Math.abs(left.getSummonedActiveSeconds() - right.getSummonedActiveSeconds()) > EPSILON
                || Math.abs(left.getSummonedWindowAwardedXp() - right.getSummonedWindowAwardedXp()) > EPSILON
                || left.getSummonedWindowStartedAtMs() != right.getSummonedWindowStartedAtMs()
                || left.getSummonedLastSampleAtMs() != right.getSummonedLastSampleAtMs();
    }

    private static boolean configIdChanged(@Nonnull TameworkLevelingComponent left,
                                           @Nonnull TameworkLevelingComponent right) {
        String leftId = left.getConfigId();
        String rightId = right.getConfigId();
        if (leftId == null || leftId.isBlank()) {
            return rightId != null && !rightId.isBlank();
        }
        return rightId == null || !leftId.equalsIgnoreCase(rightId);
    }

    private static <T extends Component<EntityStore>> void putComponent(@Nonnull Ref<EntityStore> npcRef,
                                                                        @Nonnull Store<EntityStore> store,
                                                                        @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                                        @Nonnull ComponentType<EntityStore, T> componentType,
                                                                        @Nonnull T component) {
        if (commandBuffer != null) {
            commandBuffer.putComponent(npcRef, componentType, component);
            return;
        }
        store.putComponent(npcRef, componentType, component);
    }

    private static void applyTraitModifiers(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Store<EntityStore> store,
                                            @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer != null) {
            commandBuffer.run(bufferStore -> CompanionStatModifierService.applyTraitModifiers(npcRef, bufferStore));
            return;
        }
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
    }

    private static int resolveLevelFromTotalXp(@Nonnull TwLevelingConfig config, double totalXp) {
        int maxLevel = config.getLevels().getMaxLevel();
        int level = 1;
        for (int candidate = 2; candidate <= maxLevel; candidate++) {
            if (totalXp + EPSILON < resolveCumulativeXpForLevel(config, candidate)) {
                break;
            }
            level = candidate;
        }
        return level;
    }

    private static double resolveCumulativeXpForLevel(@Nonnull TwLevelingConfig config, int level) {
        int clampedLevel = Math.max(1, Math.min(level, config.getLevels().getMaxLevel()));
        double total = 0.0;
        double baseXp = config.getLevels().getBaseXp();
        double growthFactor = config.getLevels().getGrowthFactor();
        for (int currentLevel = 2; currentLevel <= clampedLevel; currentLevel++) {
            total += baseXp * Math.pow(growthFactor, currentLevel - 2);
        }
        return Math.max(0.0, total);
    }

    private static double nextLevelDeltaXp(@Nonnull TwLevelingConfig config, int level) {
        double currentLevelStartXp = resolveCumulativeXpForLevel(config, level);
        double nextLevelTotalXp = resolveCumulativeXpForLevel(config, level + 1);
        return Math.max(0.0, nextLevelTotalXp - currentLevelStartXp);
    }

    private static double sanitizeXp(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }

    @Nonnull
    private static AwardResult awardSimpleSourceXp(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nonnull SimpleXpSourceType sourceType,
                                                   @Nonnull CompanionXpSource xpSource) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return AwardResult.notApplied();
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return AwardResult.notApplied();
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return AwardResult.notApplied();
        }
        TwLevelingConfig config = TwLevelingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return AwardResult.notApplied();
        }
        TwLevelingConfig.SimpleXpSourceSettings settings = switch (sourceType) {
            case FEED -> config.getXpSources().getFeed();
            case HARVEST -> config.getXpSources().getHarvest();
            case BREEDING -> config.getXpSources().getBreeding();
        };
        if (!settings.isEnabled() || !(settings.getFlatXp() > 0.0)) {
            return AwardResult.notApplied();
        }
        Long feedXpAwardedAtMs = null;
        if (sourceType == SimpleXpSourceType.FEED) {
            TameworkLevelingComponent component = ensureLevelingComponent(npcRef, store, null, roleId);
            if (component == null) {
                return AwardResult.notApplied();
            }
            long nowMs = System.currentTimeMillis();
            if (!isFeedXpCooldownReady(component.getLastFeedXpAwardedAtMs(), nowMs, settings.getAwardCooldownSeconds())) {
                return AwardResult.notApplied();
            }
            feedXpAwardedAtMs = nowMs;
        }
        return awardXp(npcRef, store, null, roleId, xpSource, settings.getFlatXp(), feedXpAwardedAtMs);
    }

    @Nonnull
    private static String describeSimpleSourceXpReadiness(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          @Nonnull SimpleXpSourceType sourceType) {
        if (npcRef == null) {
            return "reason=missing-npc-ref";
        }
        if (!npcRef.isValid()) {
            return "reason=invalid-npc-ref";
        }
        if (store == null) {
            return "reason=missing-store";
        }
        if (!CompanionProgressionSettings.isLevelingEnabled()) {
            return "reason=leveling-system-disabled";
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return "reason=missing-role-id";
        }
        TwLevelingConfig config = TwLevelingConfig.resolveForRole(roleId);
        if (config == null) {
            return "reason=missing-leveling-config roleId=" + roleId;
        }
        if (!config.isEnabled()) {
            return "reason=leveling-config-disabled roleId=" + roleId + " configId=" + config.getId();
        }
        TwLevelingConfig.SimpleXpSourceSettings settings = switch (sourceType) {
            case FEED -> config.getXpSources().getFeed();
            case HARVEST -> config.getXpSources().getHarvest();
            case BREEDING -> config.getXpSources().getBreeding();
        };
        String sourceName = sourceType.name().toLowerCase();
        if (!settings.isEnabled()) {
            return "reason=" + sourceName + "-xp-disabled roleId=" + roleId + " configId=" + config.getId();
        }
        if (!(settings.getFlatXp() > 0.0)) {
            return "reason=" + sourceName + "-xp-zero roleId=" + roleId + " configId=" + config.getId();
        }
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return "reason=missing-leveling-component-type roleId=" + roleId + " configId=" + config.getId();
        }
        XpEligibility eligibility = resolveXpEligibility(npcRef, store);
        if (!eligibility.eligible()) {
            return "reason=" + eligibility.reason() + " roleId=" + roleId + " configId=" + config.getId();
        }
        if (sourceType == SimpleXpSourceType.FEED) {
            TameworkLevelingComponent component = ensureLevelingComponent(npcRef, store, null, roleId);
            if (component == null) {
                return "reason=missing-leveling-component roleId=" + roleId + " configId=" + config.getId();
            }
            long nowMs = System.currentTimeMillis();
            if (!isFeedXpCooldownReady(component.getLastFeedXpAwardedAtMs(), nowMs, settings.getAwardCooldownSeconds())) {
                long remainingMs = Math.max(0L, resolveFeedXpCooldownUntilMs(
                        component.getLastFeedXpAwardedAtMs(),
                        settings.getAwardCooldownSeconds()
                ) - nowMs);
                return "reason=feed-xp-cooldown roleId=" + roleId
                        + " configId=" + config.getId()
                        + " remainingMs=" + remainingMs;
            }
        }
        return "reason=ready roleId=" + roleId + " configId=" + config.getId() + " flatXp=" + settings.getFlatXp();
    }

    static boolean isFeedXpCooldownReady(long lastAwardedAtMs, long nowMs, int cooldownSeconds) {
        if (cooldownSeconds <= 0 || lastAwardedAtMs <= 0L) {
            return true;
        }
        return nowMs >= resolveFeedXpCooldownUntilMs(lastAwardedAtMs, cooldownSeconds);
    }

    static long resolveFeedXpCooldownUntilMs(long lastAwardedAtMs, int cooldownSeconds) {
        if (cooldownSeconds <= 0 || lastAwardedAtMs <= 0L) {
            return 0L;
        }
        long cooldownMs = (long) cooldownSeconds * 1000L;
        if (Long.MAX_VALUE - lastAwardedAtMs < cooldownMs) {
            return Long.MAX_VALUE;
        }
        return lastAwardedAtMs + cooldownMs;
    }

    @Nonnull
    private static XpEligibility resolveXpEligibility(@Nonnull Ref<EntityStore> npcRef,
                                                     @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            TameworkTamedComponent tamed = store.getComponent(npcRef, tamedType);
            if (tamed != null && tamed.isTamed()) {
                return XpEligibility.accepted("tamed");
            }
        }

        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
            if (owner != null && owner.hasOwner()) {
                return XpEligibility.accepted("owned");
            }
        }

        if (tamedType == null && ownerType == null) {
            return XpEligibility.rejected("missing-companion-state-component-types");
        }
        return XpEligibility.rejected("not-tamed-or-owned");
    }

    private enum SimpleXpSourceType {
        FEED,
        HARVEST,
        BREEDING
    }

    private record XpEligibility(boolean eligible,
                                 @Nonnull String reason) {
        @Nonnull
        static XpEligibility accepted(@Nonnull String reason) {
            return new XpEligibility(true, reason);
        }

        @Nonnull
        static XpEligibility rejected(@Nonnull String reason) {
            return new XpEligibility(false, reason);
        }
    }

    public record AwardResult(boolean applied,
                              double awardedXp,
                              int previousLevel,
                              int currentLevel,
                              double totalXp) {
        static AwardResult notApplied() {
            return new AwardResult(false, 0.0, 1, 1, 0.0);
        }
    }

    public record SetLevelResult(boolean applied,
                                 @Nullable String reason,
                                 int previousLevel,
                                 int currentLevel,
                                 int maxLevel,
                                 double totalXp) {
        @Nonnull
        static SetLevelResult notApplied(@Nonnull String reason) {
            return new SetLevelResult(false, reason, 1, 1, 1, 0.0);
        }
    }

    private record CreditContext(@Nullable UUID ownerUuid,
                                 @Nonnull Set<String> toolIds) {
    }

    public record LevelingSnapshot(@Nullable String configId,
                                   int level,
                                   double currentXp,
                                   double totalXp,
                                   double currentLevelStartXp,
                                   double nextLevelTotalXp,
                                   int maxLevel,
                                   boolean atMaxLevel) {
        public double nextLevelDeltaXp() {
            return atMaxLevel ? 0.0 : Math.max(0.0, nextLevelTotalXp - currentLevelStartXp);
        }
    }
}
