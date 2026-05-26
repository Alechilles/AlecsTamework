package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
        return awardXp(npcRef, store, null, amount);
    }

    @Nonnull
    public static AwardResult awardFeedXp(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store) {
        return awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.FEED);
    }

    @Nonnull
    public static AwardResult awardHarvestXp(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        return awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.HARVEST);
    }

    @Nonnull
    public static AwardResult awardBreedingXp(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store) {
        return awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.BREEDING);
    }

    @Nonnull
    public static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable String roleIdHint,
                                      double amount) {
        return awardXp(npcRef, store, null, roleIdHint, amount);
    }

    @Nonnull
    public static AwardResult awardXp(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable Store<EntityStore> store,
                                      @Nullable CommandBuffer<EntityStore> commandBuffer,
                                      @Nullable String roleIdHint,
                                      double amount) {
        if (npcRef == null || !npcRef.isValid() || store == null || !Double.isFinite(amount) || amount <= 0.0) {
            return AwardResult.notApplied();
        }
        if (!isXpEligibleLink(npcRef, store)) {
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
        double nextTotalXp = previousTotalXp + amount;
        TameworkLevelingComponent updated = normalizeComponent(
                new TameworkLevelingComponent(component.getConfigId(), component.getLevel(), component.getCurrentXp(), nextTotalXp),
                config
        );
        if (!hasMeaningfulChange(component, updated)) {
            return AwardResult.notApplied();
        }
        putComponent(npcRef, store, commandBuffer, type, updated);
        if (updated.getLevel() != previousLevel) {
            applyTraitModifiers(npcRef, store, commandBuffer);
        }
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

    private static boolean isXpEligibleLink(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return false;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, linksType);
        return links != null && links.getToolIds() != null && links.getToolIds().length > 0;
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
        return new TameworkLevelingComponent(config.getId(), resolvedLevel, currentXp, totalXp);
    }

    private static boolean hasMeaningfulChange(@Nonnull TameworkLevelingComponent left,
                                               @Nonnull TameworkLevelingComponent right) {
        return left.getLevel() != right.getLevel()
                || Math.abs(left.getCurrentXp() - right.getCurrentXp()) > EPSILON
                || Math.abs(left.getTotalXp() - right.getTotalXp()) > EPSILON;
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

    private static double sanitizeXp(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }

    @Nonnull
    private static AwardResult awardSimpleSourceXp(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nonnull SimpleXpSourceType sourceType) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
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
        return awardXp(npcRef, store, roleId, settings.getFlatXp());
    }

    private enum SimpleXpSourceType {
        FEED,
        HARVEST,
        BREEDING
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
