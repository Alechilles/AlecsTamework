package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashSet;
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

    @Nonnull
    public static PurchaseResult purchaseTalent(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nullable String talentId) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return PurchaseResult.invalid("Companion is not available.");
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
        TameworkTalentsComponent existing = store.getComponent(npcRef, type);
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

    public static double resolvePurchasedEffectMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          @Nullable String effectKey,
                                                          double defaultMultiplier) {
        if (npcRef == null || !npcRef.isValid() || store == null || effectKey == null || effectKey.isBlank()) {
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

    @Nullable
    public static TwTalentConfig resolveTalentConfig(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
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
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwTalentConfig byId = TwTalentConfig.resolveById(component.getConfigId());
            if (byId != null) {
                return byId;
            }
        }
        return roleId == null || roleId.isBlank() ? null : TwTalentConfig.resolveForRole(roleId);
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
}
