package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the level, talent, and trait contributions behind progression stat multipliers.
 */
public final class CompanionProgressionModifierBreakdownService {
    private static final double EPSILON = 0.000001;
    private static final List<String> PRIORITIZED_EFFECT_KEYS = List.of(
            "MaxHealthMultiplier",
            "MoveSpeedMultiplier",
            "DamageDealtMultiplier",
            "DamageTakenMultiplier",
            "HarvestDoubleDropChanceMultiplier",
            "FertilityMultiplier",
            "HappinessGainMultiplier",
            "HappinessDecayMultiplier",
            "BreedCooldownMultiplier",
            "NeedsDecayMultiplier",
            "ReviveCooldownMultiplier",
            "TraitMutationChanceMultiplier",
            "AppearanceMutationChanceMultiplier",
            "HarvestCooldownMultiplier",
            "SizeMultiplier"
    );

    private CompanionProgressionModifierBreakdownService() {
    }

    @Nonnull
    public static List<ModifierBreakdown> resolveBreakdowns(@Nullable Ref<EntityStore> npcRef,
                                                            @Nullable Store<EntityStore> store,
                                                            @Nullable String roleIdHint) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return List.of();
        }
        LinkedHashSet<String> effectKeys = new LinkedHashSet<>(PRIORITIZED_EFFECT_KEYS);
        String roleId = roleIdHint;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        collectLevelingEffectKeys(effectKeys, npcRef, store, roleId);
        collectTalentEffectKeys(effectKeys, npcRef, store, roleId);
        collectTraitEffectKeys(effectKeys, npcRef, store, roleId);

        ArrayList<ModifierBreakdown> out = new ArrayList<>();
        for (String effectKey : effectKeys) {
            if (effectKey == null || effectKey.isBlank()) {
                continue;
            }
            double level = sanitizeMultiplier(CompanionLevelingService.resolveLevelGrowthMultiplier(
                    npcRef,
                    store,
                    effectKey,
                    1.0
            ));
            double talents = sanitizeMultiplier(CompanionTalentService.resolvePurchasedEffectMultiplier(
                    npcRef,
                    store,
                    effectKey,
                    1.0
            ));
            double traits = sanitizeMultiplier(TraitModifierService.resolveMultiplier(
                    npcRef,
                    store,
                    effectKey,
                    1.0
            ));
            double total = sanitizeMultiplier(level * talents * traits);
            if (isNeutral(total) && isNeutral(level) && isNeutral(talents) && isNeutral(traits)) {
                continue;
            }
            out.add(new ModifierBreakdown(effectKey, total, level, talents, traits));
        }
        return out;
    }

    private static void collectLevelingEffectKeys(@Nonnull LinkedHashSet<String> effectKeys,
                                                  @Nonnull Ref<EntityStore> npcRef,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nullable String roleId) {
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        TameworkLevelingComponent component = type != null ? store.getComponent(npcRef, type) : null;
        TwLevelingConfig config = resolveLevelingConfig(component, roleId);
        if (config == null || !config.isEnabled()) {
            return;
        }
        for (TwLevelingConfig.GrowthEffect effect : config.getStatGrowth().getEffects()) {
            if (effect != null && effect.getEffectKey() != null && !effect.getEffectKey().isBlank()) {
                effectKeys.add(effect.getEffectKey());
            }
        }
    }

    private static void collectTalentEffectKeys(@Nonnull LinkedHashSet<String> effectKeys,
                                                @Nonnull Ref<EntityStore> npcRef,
                                                @Nonnull Store<EntityStore> store,
                                                @Nullable String roleId) {
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent component = type != null ? store.getComponent(npcRef, type) : null;
        if (component == null || component.getPurchasedTalentIds().length == 0) {
            return;
        }
        TwTalentConfig config = resolveTalentConfig(component, roleId);
        if (config == null || !config.isEnabled()) {
            return;
        }
        for (String talentId : component.getPurchasedTalentIds()) {
            TwTalentConfig.TalentDefinition talent = config.findTalent(talentId);
            if (talent == null) {
                continue;
            }
            for (TwTalentConfig.PassiveEffect effect : talent.getEffects()) {
                if (effect != null && effect.getEffectKey() != null && !effect.getEffectKey().isBlank()) {
                    effectKeys.add(effect.getEffectKey());
                }
            }
        }
    }

    private static void collectTraitEffectKeys(@Nonnull LinkedHashSet<String> effectKeys,
                                               @Nonnull Ref<EntityStore> npcRef,
                                               @Nonnull Store<EntityStore> store,
                                               @Nullable String roleId) {
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent component = type != null ? store.getComponent(npcRef, type) : null;
        if (component == null || component.getTraitValues().length == 0) {
            return;
        }
        TwTraitConfig config = resolveTraitConfig(component, roleId);
        if (config == null) {
            return;
        }
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition != null && definition.getEffectKey() != null && !definition.getEffectKey().isBlank()) {
                effectKeys.add(definition.getEffectKey());
            }
        }
    }

    @Nullable
    private static TwLevelingConfig resolveLevelingConfig(@Nullable TameworkLevelingComponent component,
                                                          @Nullable String roleId) {
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwLevelingConfig byId = TwLevelingConfig.resolveById(component.getConfigId());
            if (byId != null) {
                return byId;
            }
        }
        return roleId == null || roleId.isBlank() ? null : TwLevelingConfig.resolveForRole(roleId);
    }

    @Nullable
    private static TwTalentConfig resolveTalentConfig(@Nullable TameworkTalentsComponent component,
                                                      @Nullable String roleId) {
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwTalentConfig byId = TwTalentConfig.resolveById(component.getConfigId());
            if (byId != null) {
                return byId;
            }
        }
        return roleId == null || roleId.isBlank() ? null : TwTalentConfig.resolveForRole(roleId);
    }

    @Nullable
    private static TwTraitConfig resolveTraitConfig(@Nullable TameworkTraitsComponent component,
                                                    @Nullable String roleId) {
        if (roleId != null && !roleId.isBlank()) {
            TwTraitConfig byRole = TwTraitConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            return TwTraitConfig.resolveById(component.getConfigId());
        }
        return null;
    }

    private static double sanitizeMultiplier(double value) {
        return Double.isFinite(value) ? value : 1.0;
    }

    private static boolean isNeutral(double multiplier) {
        return Math.abs(multiplier - 1.0) <= EPSILON;
    }

    public record ModifierBreakdown(@Nonnull String effectKey,
                                    double totalMultiplier,
                                    double levelMultiplier,
                                    double talentMultiplier,
                                    double traitMultiplier) {
    }
}
