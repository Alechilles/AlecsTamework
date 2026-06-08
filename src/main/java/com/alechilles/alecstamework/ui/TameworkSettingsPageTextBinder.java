package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/**
 * Binds static /tw settings page copy through bundled language keys.
 */
final class TameworkSettingsPageTextBinder {
    private static final String[][] TEXT_BINDINGS = {
            {"#TwSettingsTitle", "tamework.ui.settings.title"},
            {"#TwSettingsRefreshButton", "tamework.ui.shared.button.refresh"},
            {"#TwSettingsCloseButton", "tamework.ui.shared.button.close"},
            {"#TwSettingsLoadPresetButton", "tamework.ui.settings.button.loadPreset"},
            {"#TwSettingsExperiencePresetsLabel", "tamework.ui.settings.label.experiencePresets"},
            {"#TwSettingsLoadPresetLabel", "tamework.ui.settings.label.loadPreset"},
            {"#TwSettingsPresetNoteLabel", "tamework.ui.settings.note.presetScope"},
            {"#TwSettingsRecallSectionLabel", "tamework.ui.settings.label.recallSection"},
            {"#TwSettingsRecallTeleportingLabel", "tamework.ui.settings.label.recallTeleporting"},
            {"#TwSettingsCompanionSystemsLabel", "tamework.ui.settings.label.companionSystems"},
            {"#TwSettingsNeedsEnabledLabel", "tamework.ui.settings.label.needsEnabled"},
            {"#TwSettingsHappinessEnabledLabel", "tamework.ui.settings.label.happinessEnabled"},
            {"#TwSettingsPassiveBreedingEnabledLabel", "tamework.ui.settings.label.passiveBreedingEnabled"},
            {"#TwSettingsBreedingRequiresHappinessLabel", "tamework.ui.settings.label.breedingRequiresHappiness"},
            {"#TwSettingsLevelingEnabledLabel", "tamework.ui.settings.label.levelingEnabled"},
            {"#TwSettingsTalentsEnabledLabel", "tamework.ui.settings.label.talentsEnabled"},
            {"#TwSettingsBreedingGenderEnabledLabel", "tamework.ui.settings.label.breedingGenderEnabled"},
            {"#TwSettingsTraitsEnabledLabel", "tamework.ui.settings.label.traitsEnabled"},
            {"#TwSettingsNeedsSectionLabel", "tamework.ui.settings.label.needsSection"},
            {"#TwSettingsNeedsDamageEnabledLabel", "tamework.ui.settings.label.needsDamageEnabled"},
            {"#TwSettingsNeedsTickPolicyModeLabel", "tamework.ui.settings.label.needsTickPolicy"},
            {"#TwSettingsNeedsOwnerOfflineGraceHoursLabel", "tamework.ui.settings.label.ownerOfflineGraceHours"},
            {"#TwSettingsNeedsOwnerOfflineDecayMultiplierLabel", "tamework.ui.settings.label.ownerOfflineDecayMultiplier"},
            {"#TwSettingsNeedsDamageModelLabel", "tamework.ui.settings.label.needsDamageModel"},
            {"#TwSettingsNeedsDamageDualNeedRuleLabel", "tamework.ui.settings.label.dualNeedRule"},
            {"#TwSettingsNeedsStarvationDamagePerMinuteLabel", "tamework.ui.settings.label.starvationDamagePerMinute"},
            {"#TwSettingsNeedsDehydrationDamagePerMinuteLabel", "tamework.ui.settings.label.dehydrationDamagePerMinute"},
            {"#TwSettingsNeedsDamageLethalLabel", "tamework.ui.settings.label.needsDamageLethal"},
            {"#TwSettingsOwnershipSectionLabel", "tamework.ui.settings.label.ownership"},
            {"#TwSettingsDamageProtectionSectionLabel", "tamework.ui.settings.label.damageProtection"},
            {"#TwSettingsBlockOwnerDamageLabel", "tamework.ui.settings.label.blockOwnerDamage"},
            {"#TwSettingsBlockAllDamageIfOwnedLabel", "tamework.ui.settings.label.blockAllDamageIfOwned"},
            {"#TwSettingsInvulnerableIfOwnedLabel", "tamework.ui.settings.label.invulnerableIfOwned"},
            {"#TwSettingsCaptureSectionLabel", "tamework.ui.settings.label.capture"},
            {"#TwSettingsCaptureRequiresOwnerLabel", "tamework.ui.settings.label.captureRequiresOwner"},
            {"#TwSettingsSpawnRequiresOwnerLabel", "tamework.ui.settings.label.spawnRequiresOwner"},
            {"#TwSettingsCaptureClearsOwnerLabel", "tamework.ui.settings.label.captureClearsOwner"},
            {"#TwSettingsSpawnSetsOwnerLabel", "tamework.ui.settings.label.spawnSetsOwner"},
            {"#TwSettingsCaptureTradeNoteLabel", "tamework.ui.settings.note.captureTrade"},
            {"#TwSettingsGeneralSectionLabel", "tamework.ui.settings.label.general"},
            {"#TwSettingsInteractionRequiresOwnerLabel", "tamework.ui.settings.label.interactionRequiresOwner"},
            {"#TwSettingsLinkingRequiresOwnerLabel", "tamework.ui.settings.label.linkingRequiresOwner"},
            {"#TwSettingsReviveSectionLabel", "tamework.ui.settings.label.reviveSystem"},
            {"#TwSettingsReviveSystemEnabledLabel", "tamework.ui.settings.label.reviveSystemEnabled"},
            {"#TwSettingsPopulationSectionLabel", "tamework.ui.settings.label.population"},
            {"#TwSettingsPopulationLimitLabel", "tamework.ui.settings.label.populationLimit"},
            {"#TwSettingsPopulationScopeLabel", "tamework.ui.settings.label.populationScope"},
            {"#TwSettingsSimpleClaimsSectionLabel", "tamework.ui.settings.label.simpleClaims"},
            {"#TwSettingsSimpleClaimsEnabledLabel", "tamework.ui.settings.label.simpleClaimsEnabled"},
            {"#TwSettingsClaimLimitChunkLabel", "tamework.ui.settings.label.claimLimitChunk"},
            {"#TwSettingsClaimLimitTotalLabel", "tamework.ui.settings.label.claimLimitTotal"},
            {"#TwSettingsBreedingRequiresClaimLabel", "tamework.ui.settings.label.breedingRequiresClaim"},
            {"#TwSettingsSimpleClaimsProtectLabel", "tamework.ui.settings.label.simpleClaimsProtect"},
            {"#TwSettingsTelemetrySectionLabel", "tamework.ui.settings.label.telemetrySection"},
            {"#TwSettingsTelemetryEnabledLabel", "tamework.ui.settings.label.telemetryEnabled"},
            {"#TwSettingsTelemetryBreadcrumbsEnabledLabel", "tamework.ui.settings.label.telemetryBreadcrumbsEnabled"}
    };

    private static final String[][] TOOLTIP_BINDINGS = {
            {"#TwSettingsExperiencePresetsTooltip", "tamework.ui.settings.tooltip.experiencePresets"},
            {"#TwSettingsLoadPresetTooltip", "tamework.ui.settings.tooltip.loadPreset"},
            {"#TwSettingsPresetNoteTooltip", "tamework.ui.settings.tooltip.presetScope"},
            {"#TwSettingsRecallSectionTooltip", "tamework.ui.settings.tooltip.recallSection"},
            {"#TwSettingsRecallTeleportingTooltip", "tamework.ui.settings.tooltip.recallTeleporting"},
            {"#TwSettingsCompanionSystemsTooltip", "tamework.ui.settings.tooltip.companionSystems"},
            {"#TwSettingsNeedsEnabledTooltip", "tamework.ui.settings.tooltip.needsEnabled"},
            {"#TwSettingsHappinessEnabledTooltip", "tamework.ui.settings.tooltip.happinessEnabled"},
            {"#TwSettingsPassiveBreedingTooltip", "tamework.ui.settings.tooltip.passiveBreedingEnabled"},
            {"#TwSettingsBreedingRequiresHappinessTooltip", "tamework.ui.settings.tooltip.breedingRequiresHappiness"},
            {"#TwSettingsLevelingEnabledTooltip", "tamework.ui.settings.tooltip.levelingEnabled"},
            {"#TwSettingsTalentsEnabledTooltip", "tamework.ui.settings.tooltip.talentsEnabled"},
            {"#TwSettingsBreedingGenderTooltip", "tamework.ui.settings.tooltip.breedingGenderEnabled"},
            {"#TwSettingsTraitsEnabledTooltip", "tamework.ui.settings.tooltip.traitsEnabled"},
            {"#TwSettingsNeedsSectionTooltip", "tamework.ui.settings.tooltip.needsSection"},
            {"#TwSettingsNeedsDamageEnabledTooltip", "tamework.ui.settings.tooltip.needsDamageEnabled"},
            {"#TwSettingsNeedsTickPolicyTooltip", "tamework.ui.settings.tooltip.needsTickPolicy"},
            {"#TwSettingsNeedsOfflineGraceTooltip", "tamework.ui.settings.tooltip.ownerOfflineGraceHours"},
            {"#TwSettingsNeedsOfflineDecayMultiplierTooltip", "tamework.ui.settings.tooltip.ownerOfflineDecayMultiplier"},
            {"#TwSettingsNeedsDamageModelTooltip", "tamework.ui.settings.tooltip.needsDamageModel"},
            {"#TwSettingsNeedsDualNeedRuleTooltip", "tamework.ui.settings.tooltip.dualNeedRule"},
            {"#TwSettingsStarvationDamageTooltip", "tamework.ui.settings.tooltip.starvationDamagePerMinute"},
            {"#TwSettingsDehydrationDamageTooltip", "tamework.ui.settings.tooltip.dehydrationDamagePerMinute"},
            {"#TwSettingsNeedsDamageLethalTooltip", "tamework.ui.settings.tooltip.needsDamageLethal"},
            {"#TwSettingsOwnershipSectionTooltip", "tamework.ui.settings.tooltip.ownership"},
            {"#TwSettingsOwnershipDamageProtectionTooltip", "tamework.ui.settings.tooltip.damageProtection"},
            {"#TwSettingsBlockOwnerDamageTooltip", "tamework.ui.settings.tooltip.blockOwnerDamage"},
            {"#TwSettingsBlockAllPlayerDamageTooltip", "tamework.ui.settings.tooltip.blockAllDamageIfOwned"},
            {"#TwSettingsInvulnerableIfOwnedTooltip", "tamework.ui.settings.tooltip.invulnerableIfOwned"},
            {"#TwSettingsOwnershipCaptureTooltip", "tamework.ui.settings.tooltip.capture"},
            {"#TwSettingsCaptureRequiresOwnerTooltip", "tamework.ui.settings.tooltip.captureRequiresOwner"},
            {"#TwSettingsSpawnRequiresOwnerTooltip", "tamework.ui.settings.tooltip.spawnRequiresOwner"},
            {"#TwSettingsCaptureClearsOwnerTooltip", "tamework.ui.settings.tooltip.captureClearsOwner"},
            {"#TwSettingsSpawnSetsOwnerTooltip", "tamework.ui.settings.tooltip.spawnSetsOwner"},
            {"#TwSettingsOwnershipTradingNoteTooltip", "tamework.ui.settings.tooltip.captureTrade"},
            {"#TwSettingsOwnershipGeneralTooltip", "tamework.ui.settings.tooltip.general"},
            {"#TwSettingsInteractionRequiresOwnerTooltip", "tamework.ui.settings.tooltip.interactionRequiresOwner"},
            {"#TwSettingsLinkingRequiresOwnerTooltip", "tamework.ui.settings.tooltip.linkingRequiresOwner"},
            {"#TwSettingsReviveSectionTooltip", "tamework.ui.settings.tooltip.reviveSystem"},
            {"#TwSettingsReviveEnabledTooltip", "tamework.ui.settings.tooltip.reviveSystemEnabled"},
            {"#TwSettingsPopulationSectionTooltip", "tamework.ui.settings.tooltip.population"},
            {"#TwSettingsPopulationLimitTooltip", "tamework.ui.settings.tooltip.populationLimit"},
            {"#TwSettingsPopulationScopeTooltip", "tamework.ui.settings.tooltip.populationScope"},
            {"#TwSettingsSimpleClaimsSectionTooltip", "tamework.ui.settings.tooltip.simpleClaims"},
            {"#TwSettingsSimpleClaimsEnabledTooltip", "tamework.ui.settings.tooltip.simpleClaimsEnabled"},
            {"#TwSettingsClaimLimitChunkTooltip", "tamework.ui.settings.tooltip.claimLimitChunk"},
            {"#TwSettingsClaimLimitTotalTooltip", "tamework.ui.settings.tooltip.claimLimitTotal"},
            {"#TwSettingsBreedingRequiresClaimTooltip", "tamework.ui.settings.tooltip.breedingRequiresClaim"},
            {"#TwSettingsSimpleClaimsProtectTooltip", "tamework.ui.settings.tooltip.simpleClaimsProtect"},
            {"#TwSettingsCrashTelemetrySectionTooltip", "tamework.ui.settings.tooltip.telemetrySection"},
            {"#TwSettingsTelemetryEnabledTooltip", "tamework.ui.settings.tooltip.telemetryEnabled"},
            {"#TwSettingsTelemetryBreadcrumbsTooltip", "tamework.ui.settings.tooltip.telemetryBreadcrumbsEnabled"}
    };

    private static final String[][] NO_ITEMS_BINDINGS = {
            {"#TwSettingsPresetDropdown", "tamework.ui.settings.noItems.preset"},
            {"#TwSettingsNeedsTickPolicyModeDropdown", "tamework.ui.settings.noItems.policy"},
            {"#TwSettingsNeedsDamageModelDropdown", "tamework.ui.settings.noItems.model"},
            {"#TwSettingsNeedsDamageDualNeedRuleDropdown", "tamework.ui.settings.noItems.rule"},
            {"#TwSettingsPopulationScopeDropdown", "tamework.ui.settings.noItems.scope"}
    };

    private TameworkSettingsPageTextBinder() {
    }

    static void bindStaticText(@Nonnull UICommandBuilder commandBuilder, @Nonnull PlayerRef playerRef) {
        bind(commandBuilder, playerRef, TEXT_BINDINGS, ".Text");
        bind(commandBuilder, playerRef, TOOLTIP_BINDINGS, ".TooltipText");
        bind(commandBuilder, playerRef, NO_ITEMS_BINDINGS, ".NoItemsText");
    }

    private static void bind(@Nonnull UICommandBuilder commandBuilder,
                             @Nonnull PlayerRef playerRef,
                             @Nonnull String[][] bindings,
                             @Nonnull String property) {
        for (String[] binding : bindings) {
            commandBuilder.set(binding[0] + property, LocalizedText.resolve(playerRef, binding[1]));
        }
    }
}
