package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns purchased passive talents, prerequisite checks, and available talent-point resolution.
 */
public final class CompanionTalentService {
    private CompanionTalentService() {
    }

    public static int resolveAvailablePoints(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return 0;
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return 0;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return 0;
        }
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent component = type != null ? store.getComponent(npcRef, type) : null;
        TwTalentConfig talentConfig = resolveConfig(component, roleId);
        if (talentConfig == null || !talentConfig.isEnabled()) {
            return 0;
        }
        CompanionLevelingService.LevelingSnapshot leveling = CompanionLevelingService.resolveSnapshot(npcRef, store, roleId);
        if (leveling == null) {
            return 0;
        }
        int earned = CompanionLevelingService.resolveEarnedTalentPoints(leveling.level(), leveling.configId());
        int spent = component != null ? component.getSpentPoints() : 0;
        return Math.max(0, earned - spent);
    }

    @Nullable
    public static TameworkTalentsComponent ensureTalentsComponent(@Nullable Ref<EntityStore> npcRef,
                                                                 @Nullable Store<EntityStore> store,
                                                                 @Nullable String roleIdHint) {
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return null;
        }
        return reconcileTalentsComponent(npcRef, store, roleIdHint);
    }

    /**
     * Reconciles saved talent allocation state against the enabled tree for the supplied role.
     * A missing or disabled role config is intentionally a no-op so a config reload cannot erase
     * allocations while its assets are unavailable.
     */
    @Nullable
    public static TameworkTalentsComponent reconcileTalentsComponent(@Nullable Ref<EntityStore> npcRef,
                                                                      @Nullable Store<EntityStore> store,
                                                                      @Nullable String roleIdHint) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        if (type == null) {
            return null;
        }
        TameworkTalentsComponent existing = store.getComponent(npcRef, type);
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return existing;
        }
        String roleId = roleIdHint;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        TwTalentConfig config = TwTalentConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return store.getComponent(npcRef, type);
        }
        String resolvedConfigId = config.getId();
        if (resolvedConfigId == null || resolvedConfigId.isBlank()) {
            return existing;
        }
        TameworkTalentsComponent reconciled = reconcileAllocation(existing, config);
        if (existing == null || !sameState(existing, reconciled)) {
            store.putComponent(npcRef, type, reconciled);
            if (allocationChanged(existing, reconciled)) {
                CompanionStatModifierService.applyTraitModifiers(npcRef, store);
            }
        }
        return reconciled;
    }

    /**
     * Applies one config's compatibility contract to a persisted allocation. This method is
     * pure with respect to the supplied component and is package-visible for focused tests.
     */
    @Nullable
    public static TameworkTalentsComponent reconcileAllocation(@Nullable TameworkTalentsComponent existing,
                                                        @Nullable TwTalentConfig config) {
        if (config == null || !config.isEnabled()) {
            return existing;
        }
        String configId = config.getId();
        if (configId == null || configId.isBlank()) {
            return existing;
        }
        if (existing == null) {
            return new TameworkTalentsComponent(
                    configId,
                    0,
                    new String[0],
                    config.getAllocationRevision()
            );
        }
        if (isAllocationCompatible(existing, config)) {
            TameworkTalentsComponent preserved = existing.clone();
            preserved.setConfigId(configId);
            preserved.setAllocationRevision(config.getAllocationRevision());
            return preserved;
        }
        return new TameworkTalentsComponent(
                configId,
                0,
                new String[0],
                config.getAllocationRevision()
        );
    }

    public static boolean isAllocationCompatible(@Nullable TameworkTalentsComponent component,
                                                 @Nullable TwTalentConfig config) {
        if (component == null || config == null || !config.isEnabled()) {
            return false;
        }
        String configId = config.getId();
        return configId != null
                && !configId.isBlank()
                && component.getConfigId() != null
                && configId.equalsIgnoreCase(component.getConfigId().trim())
                && component.getAllocationRevision() == config.getAllocationRevision()
                && hasValidAllocation(component, config);
    }

    private static boolean hasValidAllocation(@Nonnull TameworkTalentsComponent component,
                                              @Nonnull TwTalentConfig config) {
        Set<String> purchasedIds = new java.util.HashSet<>();
        long spentCost = 0L;
        for (String rawTalentId : component.getPurchasedTalentIds()) {
            String talentId = normalizeId(rawTalentId);
            if (talentId == null || !purchasedIds.add(talentId)) {
                return false;
            }
            TwTalentConfig.TalentDefinition talent = config.findTalent(rawTalentId);
            if (talent == null) {
                return false;
            }
            spentCost += talent.getPointCost();
            if (spentCost > Integer.MAX_VALUE) {
                return false;
            }
        }
        if (spentCost != component.getSpentPoints()) {
            return false;
        }
        for (String rawTalentId : component.getPurchasedTalentIds()) {
            TwTalentConfig.TalentDefinition talent = config.findTalent(rawTalentId);
            if (talent == null) {
                return false;
            }
            for (String requiredId : talent.getRequiresTalentIds()) {
                String normalizedRequiredId = normalizeId(requiredId);
                if (normalizedRequiredId != null && !purchasedIds.contains(normalizedRequiredId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameState(@Nullable TameworkTalentsComponent left,
                                     @Nullable TameworkTalentsComponent right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return java.util.Objects.equals(left.getConfigId(), right.getConfigId())
                && left.getAllocationRevision() == right.getAllocationRevision()
                && left.getSpentPoints() == right.getSpentPoints()
                && Arrays.equals(left.getPurchasedTalentIds(), right.getPurchasedTalentIds());
    }

    private static boolean allocationChanged(@Nullable TameworkTalentsComponent before,
                                             @Nonnull TameworkTalentsComponent after) {
        if (before == null) {
            return false;
        }
        return before.getSpentPoints() != after.getSpentPoints()
                || !Arrays.equals(before.getPurchasedTalentIds(), after.getPurchasedTalentIds());
    }

    @Nullable
    private static String normalizeId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    public static PurchaseResult purchaseTalent(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nullable String talentId) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return PurchaseResult.invalid("Companion is not available.");
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return PurchaseResult.invalid("Companion talents are disabled in Tamework settings.");
        }
        if (talentId == null || talentId.isBlank()) {
            return PurchaseResult.invalid("No talent was selected.");
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return PurchaseResult.invalid("Companion role is unavailable.");
        }
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        if (type == null) {
            return PurchaseResult.invalid("Talent storage is unavailable.");
        }
        TameworkTalentsComponent existing = reconcileTalentsComponent(npcRef, store, roleId);
        TwTalentConfig config = resolveConfig(existing, roleId);
        if (config == null || !config.isEnabled()) {
            return PurchaseResult.invalid("No talent tree is configured for this companion.");
        }
        TwTalentConfig.TalentDefinition talent = config.findTalent(talentId);
        if (talent == null) {
            return PurchaseResult.invalid("That talent could not be found.");
        }
        TameworkTalentsComponent component = existing != null
                ? existing.clone()
                : new TameworkTalentsComponent(config.getId(), 0, new String[0]);
        component.setConfigId(config.getId());
        component.setAllocationRevision(config.getAllocationRevision());
        if (component.hasPurchasedTalent(talent.getId())) {
            return PurchaseResult.invalid("That talent is already unlocked.");
        }
        CompanionLevelingService.LevelingSnapshot leveling = CompanionLevelingService.resolveSnapshot(npcRef, store, roleId);
        if (leveling == null) {
            return PurchaseResult.invalid("Level data is unavailable for this companion.");
        }
        if (leveling.level() < talent.getMinLevel()) {
            return PurchaseResult.invalid("This talent requires a higher level.");
        }
        if (!hasPrerequisites(component, talent)) {
            return PurchaseResult.invalid("This talent requires another talent first.");
        }
        int availablePoints = resolveAvailablePoints(npcRef, store);
        if (availablePoints < talent.getPointCost()) {
            return PurchaseResult.invalid("Not enough talent points are available.");
        }
        Set<String> updatedTalents = new LinkedHashSet<>();
        for (String purchasedTalentId : component.getPurchasedTalentIds()) {
            updatedTalents.add(purchasedTalentId);
        }
        updatedTalents.add(talent.getId());
        component.setPurchasedTalentIds(updatedTalents.toArray(new String[0]));
        component.setSpentPoints(component.getSpentPoints() + talent.getPointCost());
        store.putComponent(npcRef, type, component);
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
        return PurchaseResult.applied(component, resolveAvailablePoints(npcRef, store));
    }

    @Nonnull
    public static ResetResult resetTalents(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return ResetResult.invalid("Companion is not available.");
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return ResetResult.invalid("Companion talents are disabled in Tamework settings.");
        }
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        if (type == null) {
            return ResetResult.invalid("Talent storage is unavailable.");
        }
        TameworkTalentsComponent existing = store.getComponent(npcRef, type);
        if (existing == null || (existing.getSpentPoints() <= 0 && existing.getPurchasedTalentIds().length == 0)) {
            return ResetResult.invalid("No talent points are spent.");
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwTalentConfig config = resolveRoleConfig(roleId);
        TameworkTalentsComponent reconciled = reconcileAllocation(existing, config);
        TameworkTalentsComponent component = reconciled == null ? existing.clone() : reconciled.clone();
        if (config != null) {
            component.setConfigId(config.getId());
            component.setAllocationRevision(config.getAllocationRevision());
        }
        component.setSpentPoints(0);
        component.setPurchasedTalentIds(new String[0]);
        store.putComponent(npcRef, type, component);
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
        return ResetResult.applied(component, resolveAvailablePoints(npcRef, store));
    }

    public static double resolvePurchasedEffectMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          @Nullable String effectKey,
                                                          double defaultMultiplier) {
        if (npcRef == null || !npcRef.isValid() || store == null || effectKey == null || effectKey.isBlank()) {
            return defaultMultiplier;
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return defaultMultiplier;
        }
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent component = type != null ? store.getComponent(npcRef, type) : null;
        if (component == null || component.getPurchasedTalentIds().length == 0) {
            return defaultMultiplier;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwTalentConfig config = resolveConfig(component, roleId);
        if (config == null || !config.isEnabled()) {
            return defaultMultiplier;
        }
        double multiplier = defaultMultiplier;
        boolean matched = false;
        for (String talentId : component.getPurchasedTalentIds()) {
            TwTalentConfig.TalentDefinition talent = config.findTalent(talentId);
            if (talent == null) {
                continue;
            }
            for (TwTalentConfig.PassiveEffect effect : talent.getEffects()) {
                if (effect == null || effect.getEffectKey() == null || !effect.getEffectKey().equalsIgnoreCase(effectKey)) {
                    continue;
                }
                matched = true;
                multiplier *= effect.getMultiplier();
            }
        }
        return matched ? multiplier : defaultMultiplier;
    }

    public static double resolvePurchasedEffectMultiplier(@Nullable TwTalentConfig config,
                                                          @Nullable String[] purchasedTalentIds,
                                                          @Nullable String effectKey,
                                                          double defaultMultiplier) {
        if (config == null || !config.isEnabled()
                || purchasedTalentIds == null
                || purchasedTalentIds.length == 0
                || effectKey == null
                || effectKey.isBlank()) {
            return defaultMultiplier;
        }
        double multiplier = defaultMultiplier;
        boolean matched = false;
        for (String talentId : purchasedTalentIds) {
            TwTalentConfig.TalentDefinition talent = config.findTalent(talentId);
            if (talent == null) {
                continue;
            }
            for (TwTalentConfig.PassiveEffect effect : talent.getEffects()) {
                if (effect == null || effect.getEffectKey() == null || !effect.getEffectKey().equalsIgnoreCase(effectKey)) {
                    continue;
                }
                matched = true;
                multiplier *= effect.getMultiplier();
            }
        }
        return matched ? multiplier : defaultMultiplier;
    }

    @Nullable
    public static TwTalentConfig resolveTalentConfig(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        if (!CompanionProgressionSettings.isTalentsEnabled()) {
            return null;
        }
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent component = type != null ? store.getComponent(npcRef, type) : null;
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        return resolveConfig(component, roleId);
    }

    @Nullable
    private static TwTalentConfig resolveConfig(@Nullable TameworkTalentsComponent component,
                                                @Nullable String roleId) {
        TwTalentConfig roleConfig = resolveRoleConfig(roleId);
        if (roleConfig != null) {
            return roleConfig;
        }
        if (roleId != null && !roleId.isBlank()) {
            return null;
        }
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwTalentConfig byId = TwTalentConfig.resolveById(component.getConfigId());
            if (byId != null && byId.isEnabled()) {
                return byId;
            }
        }
        return null;
    }

    @Nullable
    private static TwTalentConfig resolveRoleConfig(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        TwTalentConfig config = TwTalentConfig.resolveForRole(roleId);
        return config != null && config.isEnabled() ? config : null;
    }

    private static boolean hasPrerequisites(@Nonnull TameworkTalentsComponent component,
                                            @Nonnull TwTalentConfig.TalentDefinition talent) {
        for (String requiredTalentId : talent.getRequiresTalentIds()) {
            if (requiredTalentId == null || requiredTalentId.isBlank()) {
                continue;
            }
            if (!component.hasPurchasedTalent(requiredTalentId)) {
                return false;
            }
        }
        return true;
    }

    public record PurchaseResult(boolean applied,
                                 @Nonnull String message,
                                 @Nullable TameworkTalentsComponent component,
                                 int availablePoints) {
        static PurchaseResult applied(@Nonnull TameworkTalentsComponent component, int availablePoints) {
            return new PurchaseResult(true, "Talent unlocked.", component, availablePoints);
        }

        static PurchaseResult invalid(@Nonnull String message) {
            return new PurchaseResult(false, message, null, 0);
        }
    }

    public record ResetResult(boolean applied,
                              @Nonnull String message,
                              @Nullable TameworkTalentsComponent component,
                              int availablePoints) {
        static ResetResult applied(@Nonnull TameworkTalentsComponent component, int availablePoints) {
            return new ResetResult(true, "Talent points refunded.", component, availablePoints);
        }

        static ResetResult invalid(@Nonnull String message) {
            return new ResetResult(false, message, null, 0);
        }
    }
}
