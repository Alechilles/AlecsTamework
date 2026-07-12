package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRequest;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.NeedsResourceMode;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Parses and validates settings-page form values independently from page lifecycle orchestration.
 */
final class TameworkSettingsFormParser {
    private final PlayerRef playerRef;
    private final HytaleLogger logger;

    TameworkSettingsFormParser(@Nonnull PlayerRef playerRef, @Nonnull HytaleLogger logger) {
        this.playerRef = playerRef;
        this.logger = logger;
    }

    @Nonnull
    ParseResult parse(@Nonnull TameworkSettingsPage.EventPayload payload,
                      @Nonnull TameworkSettingsValues currentValues,
                      @Nonnull ClaimProviderRequest currentClaimProviderRequest) {
        NumericResult numericResult = parseNumbers(payload);
        if (!numericResult.success()) {
            return ParseResult.failure(numericResult.message());
        }
        ChoiceResult choiceResult = resolveChoices(payload, currentValues, currentClaimProviderRequest);
        if (!choiceResult.success()) {
            return ParseResult.failure(choiceResult.message());
        }
        return ParseResult.success(buildValues(payload, currentValues, numericResult.values(), choiceResult.values()));
    }

    @Nonnull
    private NumericResult parseNumbers(@Nonnull TameworkSettingsPage.EventPayload payload) {
        ValueResult<Integer> populationLimit = parseNonNegativeInt(
                payload.populationLimit, "tamework.ui.settings.field.populationLimit");
        if (!populationLimit.success()) {
            return NumericResult.failure(populationLimit.message());
        }
        ValueResult<Integer> claimLimitChunk = parseNonNegativeInt(
                payload.claimLimitChunk, "tamework.ui.settings.field.simpleClaimsClaimChunkLimit");
        if (!claimLimitChunk.success()) {
            return NumericResult.failure(claimLimitChunk.message());
        }
        ValueResult<Integer> claimLimitTotal = parseNonNegativeInt(
                payload.claimLimitTotal, "tamework.ui.settings.field.simpleClaimsClaimTotalLimit");
        if (!claimLimitTotal.success()) {
            return NumericResult.failure(claimLimitTotal.message());
        }
        ValueResult<Double> offlineGraceHours = parseNonNegativeDouble(
                payload.needsOwnerOfflineGraceHours, "tamework.ui.settings.field.needsOwnerOfflineGraceHours");
        if (!offlineGraceHours.success()) {
            return NumericResult.failure(offlineGraceHours.message());
        }
        ValueResult<Double> offlineDecayMultiplier = parseNonNegativeDouble(
                payload.needsOwnerOfflineDecayMultiplier,
                "tamework.ui.settings.field.needsOwnerOfflineDecayMultiplier");
        if (!offlineDecayMultiplier.success()) {
            return NumericResult.failure(offlineDecayMultiplier.message());
        }
        ValueResult<Double> starvationDamage = parseNonNegativeDouble(
                payload.needsStarvationDamagePerMinute,
                "tamework.ui.settings.field.needsStarvationDamagePerMinute");
        if (!starvationDamage.success()) {
            return NumericResult.failure(starvationDamage.message());
        }
        ValueResult<Double> dehydrationDamage = parseNonNegativeDouble(
                payload.needsDehydrationDamagePerMinute,
                "tamework.ui.settings.field.needsDehydrationDamagePerMinute");
        if (!dehydrationDamage.success()) {
            return NumericResult.failure(dehydrationDamage.message());
        }
        return NumericResult.success(new NumericValues(
                populationLimit.value(), claimLimitChunk.value(), claimLimitTotal.value(),
                offlineGraceHours.value(), offlineDecayMultiplier.value(),
                starvationDamage.value(), dehydrationDamage.value()
        ));
    }

    @Nonnull
    private ChoiceResult resolveChoices(@Nonnull TameworkSettingsPage.EventPayload payload,
                                        @Nonnull TameworkSettingsValues currentValues,
                                        @Nonnull ClaimProviderRequest currentClaimProviderRequest) {
        ClaimProviderRequest providerRequest = TameworkSettingsValidation.resolveClaimProvider(
                payload.claimProvider,
                currentClaimProviderRequest
        );
        if (!providerRequest.valid()) {
            return ChoiceResult.failure(format(
                    "tamework.ui.settings.validation.claimProvider",
                    providerRequest.displayValue()
            ));
        }
        String tickPolicy = fallback(
                payload.needsTickPolicyMode,
                currentValues.needsTickPolicyMode().toConfigValue()
        );
        String resourceMode = fallback(payload.needsResourceMode, currentValues.needsResourceMode());
        String damageModel = fallback(
                payload.needsDamageModel,
                currentValues.needsDamageModel().toConfigValue()
        );
        String dualNeedRule = fallback(
                payload.needsDamageDualNeedRule,
                currentValues.needsDamageDualNeedRule().toConfigValue()
        );
        return ChoiceResult.success(new ChoiceValues(
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(payload.populationScope),
                providerRequest.provider(),
                TwNeedsConfig.TickPolicyMode.fromConfigValue(tickPolicy),
                NeedsResourceMode.fromConfigValue(resourceMode).toConfigValue(),
                TwNeedsConfig.DamageModel.fromConfigValue(damageModel),
                TwNeedsConfig.DualNeedRule.fromConfigValue(dualNeedRule)
        ));
    }

    @Nonnull
    private TameworkSettingsValues buildValues(@Nonnull TameworkSettingsPage.EventPayload payload,
                                                @Nonnull TameworkSettingsValues current,
                                                @Nonnull NumericValues numbers,
                                                @Nonnull ChoiceValues choices) {
        return new TameworkSettingsValues(
                numbers.populationLimit(),
                choices.populationScope(),
                choices.claimProvider(),
                boolOrDefault(payload.simpleClaimsEnabled, current.simpleClaimsEnabled()),
                numbers.claimLimitChunk(),
                numbers.claimLimitTotal(),
                boolOrDefault(payload.breedingRequiresClaim, current.simpleClaimsBreedingRequiresClaim()),
                boolOrDefault(payload.simpleClaimsProtect, current.simpleClaimsProtectTamedFromNonMembers()),
                boolOrDefault(payload.blockOwnerDamage, current.blockOwnerDamage()),
                boolOrDefault(payload.blockAllDamageIfOwned, current.blockAllPlayerDamageIfOwned()),
                boolOrDefault(payload.invulnerableIfOwned, current.invulnerableIfOwned()),
                boolOrDefault(payload.captureClearsOwner, current.captureClearsOwner()),
                boolOrDefault(payload.spawnSetsOwner, current.spawnSetsOwner()),
                boolOrDefault(payload.captureRequiresOwner, current.captureRequiresOwner()),
                boolOrDefault(payload.spawnRequiresOwner, current.spawnRequiresOwner()),
                boolOrDefault(payload.interactionRequiresOwner, current.interactionRequiresOwner()),
                boolOrDefault(payload.linkingRequiresOwner, current.linkingRequiresOwner()),
                boolOrDefault(payload.needsEnabled, current.needsEnabled()),
                choices.needsResourceMode(),
                boolOrDefault(payload.needsDamageEnabled, current.needsDamageEnabled()),
                choices.tickPolicyMode(),
                numbers.needsOwnerOfflineGraceHours(),
                numbers.needsOwnerOfflineDecayMultiplier(),
                choices.damageModel(),
                choices.damageDualNeedRule(),
                numbers.needsStarvationDamagePerMinute(),
                numbers.needsDehydrationDamagePerMinute(),
                boolOrDefault(payload.needsDamageLethal, current.needsDamageLethal()),
                boolOrDefault(payload.happinessEnabled, current.happinessEnabled()),
                boolOrDefault(payload.passiveBreedingEnabled, current.passiveBreedingEnabled()),
                boolOrDefault(payload.breedingRequiresHappiness, current.breedingRequiresHappiness()),
                boolOrDefault(payload.breedingGenderEnabled, current.breedingGenderEnabled()),
                boolOrDefault(payload.traitsEnabled, current.traitsEnabled()),
                boolOrDefault(payload.levelingEnabled, current.levelingEnabled()),
                boolOrDefault(payload.talentsEnabled, current.talentsEnabled()),
                boolOrDefault(payload.reviveSystemEnabled, current.reviveSystemEnabled()),
                boolOrDefault(payload.recallTeleportingEnabled, current.recallTeleportingEnabled()),
                current.telemetryEnabled(),
                current.telemetryBreadcrumbsEnabled()
        );
    }

    @Nonnull
    private ValueResult<Integer> parseNonNegativeInt(@Nullable String raw, @Nonnull String labelKey) {
        String label = resolve(labelKey);
        String value = trim(raw);
        if (value.isBlank()) {
            return ValueResult.success(0);
        }
        try {
            return ValueResult.success(Math.max(0, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            logger.at(Level.FINE).log("Invalid integer input for " + label + ": " + value);
            return ValueResult.failure(validationMessage("nonNegativeInteger", label));
        }
    }

    @Nonnull
    private ValueResult<Double> parseNonNegativeDouble(@Nullable String raw, @Nonnull String labelKey) {
        String label = resolve(labelKey);
        String value = trim(raw);
        if (value.isBlank()) {
            return ValueResult.success(0.0);
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed)) {
                return ValueResult.success(Math.max(0.0, parsed));
            }
        } catch (NumberFormatException ignored) {
            // The shared validation response below covers malformed and non-finite values.
        }
        logger.at(Level.FINE).log("Invalid decimal input for " + label + ": " + value);
        return ValueResult.failure(validationMessage("nonNegativeNumber", label));
    }

    @Nonnull
    private String validationMessage(@Nonnull String validationKey, @Nonnull String label) {
        return format("tamework.ui.settings.validation." + validationKey, label);
    }

    @Nonnull
    private String fallback(@Nullable String value, @Nonnull String fallback) {
        String trimmed = trim(value);
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private static boolean boolOrDefault(@Nullable Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    @Nonnull
    private String resolve(@Nonnull String key) {
        return LocalizedText.resolve(playerRef, key);
    }

    @Nonnull
    private String format(@Nonnull String key, Object... args) {
        return LocalizedText.format(playerRef, key, args);
    }

    @Nonnull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    record ParseResult(boolean success, @Nonnull String message, @Nullable TameworkSettingsValues values) {
        static ParseResult success(@Nonnull TameworkSettingsValues values) {
            return new ParseResult(true, "", values);
        }

        static ParseResult failure(@Nonnull String message) {
            return new ParseResult(false, message, null);
        }
    }

    private record NumericValues(int populationLimit,
                                 int claimLimitChunk,
                                 int claimLimitTotal,
                                 double needsOwnerOfflineGraceHours,
                                 double needsOwnerOfflineDecayMultiplier,
                                 double needsStarvationDamagePerMinute,
                                 double needsDehydrationDamagePerMinute) {
    }

    private record NumericResult(boolean success, @Nonnull String message, @Nullable NumericValues values) {
        static NumericResult success(@Nonnull NumericValues values) {
            return new NumericResult(true, "", values);
        }

        static NumericResult failure(@Nonnull String message) {
            return new NumericResult(false, message, null);
        }
    }

    private record ChoiceValues(@Nonnull TwGlobalConfig.PerPlayerLimitScope populationScope,
                                @Nonnull ClaimIntegrationProvider claimProvider,
                                @Nonnull TwNeedsConfig.TickPolicyMode tickPolicyMode,
                                @Nonnull String needsResourceMode,
                                @Nonnull TwNeedsConfig.DamageModel damageModel,
                                @Nonnull TwNeedsConfig.DualNeedRule damageDualNeedRule) {
    }

    private record ChoiceResult(boolean success, @Nonnull String message, @Nullable ChoiceValues values) {
        static ChoiceResult success(@Nonnull ChoiceValues values) {
            return new ChoiceResult(true, "", values);
        }

        static ChoiceResult failure(@Nonnull String message) {
            return new ChoiceResult(false, message, null);
        }
    }

    private record ValueResult<T>(boolean success, @Nonnull String message, @Nullable T value) {
        static <T> ValueResult<T> success(@Nonnull T value) {
            return new ValueResult<>(true, "", value);
        }

        static <T> ValueResult<T> failure(@Nonnull String message) {
            return new ValueResult<>(false, message, null);
        }
    }
}
