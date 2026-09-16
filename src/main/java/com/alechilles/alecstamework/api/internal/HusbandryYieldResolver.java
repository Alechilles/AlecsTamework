package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryOutputConversion;
import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import javax.annotation.Nullable;

/** Resolves the additive yield and recovery terms used by shared husbandry actions. */
public final class HusbandryYieldResolver {
    private static final String FLEECE_FIBER_YIELD_EFFECT = "FleeceFiberYieldMultiplier";
    private static final String ANIMAL_PRODUCT_YIELD_EFFECT = "AnimalProductYieldMultiplier";
    private static final String HARVEST_RECOVERY_EFFECT = "HarvestRecoverySpeedMultiplier";

    private HusbandryYieldResolver() {
    }

    public static HusbandryOutcomeModifiers resolveHarvest(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable String productId,
            @Nullable HusbandryToolContext tool,
            @Nullable UUID actorId
    ) {
        return HusbandryOutcomeRuntime.resolve(
                HusbandryOutcomeKind.HARVEST_YIELD, npcRef, store, roleId, productId, tool, actorId);
    }

    public static HusbandryOutcomeModifiers resolveCull(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable String productId,
            @Nullable HusbandryToolContext tool,
            @Nullable UUID actorId
    ) {
        return HusbandryOutcomeRuntime.resolve(
                HusbandryOutcomeKind.CULL_YIELD, npcRef, store, roleId, productId, tool, actorId);
    }

    public static double harvestYieldBonus(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String productId,
            HusbandryOutcomeModifiers modifiers
    ) {
        return harvestYieldBonus(npcRef, store, productId, modifiers, 0.0);
    }

    /** Adds the legacy DropDuplicate trait expectation only when its mode permits it. */
    public static double harvestYieldBonus(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String productId,
            HusbandryOutcomeModifiers modifiers,
            double legacyDropDuplicateBonus
    ) {
        double traitBonus = resolveTraitHarvestYieldBonus(npcRef, store, productId);
        return boundedBonus(traitBonus)
                + boundedBonus(legacyDropDuplicateBonus)
                + expectedLegacyProviderBonusCopies(modifiers)
                + boundedBonus(modifiers == null ? 0.0 : modifiers.yieldBonus());
    }

    public static double cullYieldBonus(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String productId,
            HusbandryOutcomeModifiers modifiers
    ) {
        double bonus = expectedLegacyProviderBonusCopies(modifiers)
                + boundedBonus(modifiers == null ? 0.0 : modifiers.yieldBonus());
        if (isMeatOrHideProduct(productId)) {
            bonus += TraitModifierService.resolveSizeMeatHideYieldBonus(npcRef, store);
        }
        return boundedBonus(bonus);
    }

    /** Returns the additive recovery-speed term; duration is divided by one plus this value. */
    public static double harvestRecoverySpeedBonus(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable HusbandryToolContext tool,
            @Nullable String roleId,
            @Nullable UUID actorId
    ) {
        HusbandryOutcomeModifiers provider = HusbandryOutcomeRuntime.resolve(
                HusbandryOutcomeKind.HARVEST_YIELD, npcRef, store, roleId, null, tool, actorId);
        return harvestRecoverySpeedBonus(npcRef, store, provider);
    }

    /** Combines the active recovery trait with an authorization-time provider snapshot. */
    public static double harvestRecoverySpeedBonus(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable HusbandryOutcomeModifiers provider
    ) {
        double trait = TraitModifierService.resolveMultiplier(
                npcRef, store, HARVEST_RECOVERY_EFFECT, 1.0);
        return boundedRecoveryBonus(trait - 1.0)
                + boundedRecoveryBonus(provider == null ? 0.0 : provider.harvestRecoverySpeedBonus());
    }

    public static boolean isMeatOrHideProduct(@Nullable String productId) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        String normalized = productId.toLowerCase(Locale.ROOT);
        return normalized.contains("hide") || normalized.contains("leather") || normalized.contains("skin")
                || normalized.contains("meat") || normalized.contains("beef") || normalized.contains("mutton")
                || normalized.contains("pork") || normalized.contains("chicken") || normalized.contains("poultry");
    }

    public static boolean isFleeceOrFiberProduct(@Nullable String productId) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        String normalized = productId.toLowerCase(Locale.ROOT);
        return normalized.contains("fleece") || normalized.contains("wool") || normalized.contains("fiber")
                || normalized.contains("fibre") || normalized.contains("silk")
                || normalized.contains("cindercloth") || normalized.contains("shadoweave");
    }

    /** Renewable harvest products receive general animal-product traits; legacy fleece traits stay fleece-only. */
    static boolean isRenewableAnimalProduct(@Nullable String productId) {
        if (isFleeceOrFiberProduct(productId)) {
            return true;
        }
        if (productId == null || productId.isBlank()) {
            return false;
        }
        String normalized = productId.toLowerCase(Locale.ROOT);
        return normalized.contains("egg") || normalized.contains("milk");
    }

    private static double resolveTraitHarvestYieldBonus(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String productId
    ) {
        if (!isRenewableAnimalProduct(productId)) {
            return 0.0;
        }
        double generalMultiplier = TraitModifierService.resolveMultiplier(
                npcRef, store, ANIMAL_PRODUCT_YIELD_EFFECT, 1.0);
        double legacyFleeceMultiplier = isFleeceOrFiberProduct(productId)
                ? TraitModifierService.resolveMultiplier(npcRef, store, FLEECE_FIBER_YIELD_EFFECT, 1.0)
                : 1.0;
        return combineTraitHarvestYieldBonuses(productId, generalMultiplier, legacyFleeceMultiplier);
    }

    /** Internal trait resolver shared by the production path and its output-boundary behavior tests. */
    static double resolveTraitHarvestYieldBonus(
            @Nullable com.alechilles.alecstamework.npc.components.TameworkTraitsComponent traits,
            @Nullable com.alechilles.alecstamework.config.assets.TwTraitConfig traitConfig,
            @Nullable String productId
    ) {
        if (!isRenewableAnimalProduct(productId)) {
            return 0.0;
        }
        double generalMultiplier = TraitModifierService.resolveMultiplier(
                traits, traitConfig, ANIMAL_PRODUCT_YIELD_EFFECT, 1.0);
        double legacyFleeceMultiplier = isFleeceOrFiberProduct(productId)
                ? TraitModifierService.resolveMultiplier(traits, traitConfig, FLEECE_FIBER_YIELD_EFFECT, 1.0)
                : 1.0;
        return combineTraitHarvestYieldBonuses(productId, generalMultiplier, legacyFleeceMultiplier);
    }

    private static double combineTraitHarvestYieldBonuses(@Nullable String productId,
                                                           double generalMultiplier,
                                                           double legacyFleeceMultiplier) {
        if (!isRenewableAnimalProduct(productId)) {
            return 0.0;
        }
        double general = Double.isFinite(generalMultiplier) ? generalMultiplier - 1.0 : 0.0;
        double legacy = isFleeceOrFiberProduct(productId) && Double.isFinite(legacyFleeceMultiplier)
                ? legacyFleeceMultiplier - 1.0 : 0.0;
        return general + legacy;
    }

    /** Applies a provider conversion only after all additive harvest quantities are final. */
    public static CompanionOutputService.FinalizedOutput applyHarvestConversions(
            CompanionOutputService.FinalizedOutput output,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable HusbandryToolContext tool,
            @Nullable UUID actorId,
            DoubleSupplier random
    ) {
        return CompanionOutputService.applyOutputConversions(output, productId -> {
            HusbandryOutcomeModifiers modifiers = resolveHarvest(
                    npcRef, store, roleId, productId, tool, actorId);
            return modifiers.toolAuthorized() ? modifiers.outputConversion() : null;
        }, random);
    }

    /** Applies a provider conversion only after all additive cull quantities are final. */
    public static CompanionOutputService.FinalizedOutput applyCullConversions(
            CompanionOutputService.FinalizedOutput output,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable HusbandryToolContext tool,
            @Nullable UUID actorId,
            DoubleSupplier random
    ) {
        return CompanionOutputService.applyOutputConversions(output, productId -> {
            HusbandryOutcomeModifiers modifiers = resolveCull(
                    npcRef, store, roleId, productId, tool, actorId);
            return modifiers.toolAuthorized() ? modifiers.outputConversion() : null;
        }, random);
    }

    /** Preserves the historical gated provider-roll expectation without replaying rolls. */
    public static double expectedLegacyProviderBonusCopies(@Nullable HusbandryOutcomeModifiers modifiers) {
        HusbandryOutcomeModifiers safe = modifiers == null
                ? HusbandryOutcomeModifiers.identity() : modifiers;
        return clampChance(safe.bonusOutputChance())
                * (1.0 + clampChance(safe.tripleOutputChance()));
    }

    private static double boundedBonus(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(10.0, value));
    }

    private static double boundedRecoveryBonus(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-0.75, Math.min(1.0, value));
    }

    private static double clampChance(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
