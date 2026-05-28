package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Curated /tw settings page for common global settings + crash telemetry toggle.
 */
public final class TameworkSettingsPage extends InteractiveCustomUIPage<TameworkSettingsPage.EventPayload> {
    public static final String UI_PATH = "TameworkSettingsPage.ui";

    private static final String ACTION = "Action";
    private static final String ACTION_REFRESH = "Refresh";
    private static final String ACTION_APPLY = "Apply";
    private static final String ACTION_LOAD_PRESET = "LoadPreset";
    private static final String ACTION_CLOSE = "Close";

    private static final String KEY_PRESET = "@Preset";
    private static final String KEY_POP_LIMIT = "@PopulationLimit";
    private static final String KEY_POP_SCOPE = "@PopulationScope";
    private static final String KEY_SIMPLE_CLAIMS_ENABLED = "@SimpleClaimsEnabled";
    private static final String KEY_CLAIM_LIMIT_CHUNK = "@ClaimLimitChunk";
    private static final String KEY_CLAIM_LIMIT_TOTAL = "@ClaimLimitTotal";
    private static final String KEY_BREEDING_REQUIRES_CLAIM = "@BreedingRequiresClaim";
    private static final String KEY_SIMPLE_CLAIMS_PROTECT = "@SimpleClaimsProtect";
    private static final String KEY_OWNERSHIP_BLOCK_OWNER_DAMAGE = "@OwnershipBlockOwnerDamage";
    private static final String KEY_OWNERSHIP_BLOCK_ALL_DAMAGE_IF_OWNED = "@OwnershipBlockAllDamageIfOwned";
    private static final String KEY_OWNERSHIP_INVULNERABLE_IF_OWNED = "@OwnershipInvulnerableIfOwned";
    private static final String KEY_CAPTURE_CLEARS_OWNER = "@CaptureClearsOwner";
    private static final String KEY_SPAWN_SETS_OWNER = "@SpawnSetsOwner";
    private static final String KEY_CAPTURE_REQUIRES_OWNER = "@CaptureRequiresOwner";
    private static final String KEY_SPAWN_REQUIRES_OWNER = "@SpawnRequiresOwner";
    private static final String KEY_INTERACTION_REQUIRES_OWNER = "@InteractionRequiresOwner";
    private static final String KEY_LINKING_REQUIRES_OWNER = "@LinkingRequiresOwner";
    private static final String KEY_NEEDS_ENABLED = "@NeedsEnabled";
    private static final String KEY_NEEDS_DAMAGE_ENABLED = "@NeedsDamageEnabled";
    private static final String KEY_NEEDS_TICK_POLICY_MODE = "@NeedsTickPolicyMode";
    private static final String KEY_NEEDS_OWNER_OFFLINE_GRACE_HOURS = "@NeedsOwnerOfflineGraceHours";
    private static final String KEY_NEEDS_OWNER_OFFLINE_DECAY_MULTIPLIER = "@NeedsOwnerOfflineDecayMultiplier";
    private static final String KEY_NEEDS_DAMAGE_MODEL = "@NeedsDamageModel";
    private static final String KEY_NEEDS_DAMAGE_DUAL_NEED_RULE = "@NeedsDamageDualNeedRule";
    private static final String KEY_NEEDS_STARVATION_DAMAGE_PER_MINUTE = "@NeedsStarvationDamagePerMinute";
    private static final String KEY_NEEDS_DEHYDRATION_DAMAGE_PER_MINUTE = "@NeedsDehydrationDamagePerMinute";
    private static final String KEY_NEEDS_DAMAGE_LETHAL = "@NeedsDamageLethal";
    private static final String KEY_HAPPINESS_ENABLED = "@HappinessEnabled";
    private static final String KEY_PASSIVE_BREEDING_ENABLED = "@PassiveBreedingEnabled";
    private static final String KEY_BREEDING_REQUIRES_HAPPINESS = "@BreedingRequiresHappiness";
    private static final String KEY_BREEDING_GENDER_ENABLED = "@BreedingGenderEnabled";
    private static final String KEY_TRAITS_ENABLED = "@TraitsEnabled";
    private static final String KEY_LEVELING_ENABLED = "@LevelingEnabled";
    private static final String KEY_TALENTS_ENABLED = "@TalentsEnabled";
    private static final String KEY_REVIVE_SYSTEM_ENABLED = "@ReviveSystemEnabled";
    private static final String KEY_RECALL_TELEPORTING_ENABLED = "@RecallTeleportingEnabled";
    private static final String KEY_TELEMETRY_ENABLED = "@TelemetryEnabled";
    private static final String KEY_TELEMETRY_BREADCRUMBS_ENABLED = "@TelemetryBreadcrumbsEnabled";

    private final Tamework plugin;
    private final World world;

    private TameworkSettingsValues currentValues;
    private String statusLine = "";
    private String warningLine = "";
    private boolean applyInProgress;

    public TameworkSettingsPage(@Nonnull PlayerRef playerRef,
                                @Nonnull Tamework plugin,
                                @Nonnull World world) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.currentValues = TameworkSettingsValues.fromRuntime();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        try {
            commandBuilder.append(UI_PATH);
            bindStaticEvents(eventBuilder);
            render(commandBuilder);
        } catch (Throwable throwable) {
            plugin.getTelemetryEvents().recordError(
                    "ui_page_build_failed",
                    throwable,
                    TameworkTelemetryEvents.featureContext("settings", "settings_page", "/tw settings")
                            .operation("build")
                            .target("TameworkSettingsPage")
                            .detail("Failed to build Tamework settings page.")
                            .detail("source", "settings_ui")
                            .build()
            );
            throw throwable;
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        String action = trim(data.action);
        switch (action) {
            case ACTION_CLOSE -> close();
            case ACTION_REFRESH -> {
                currentValues = TameworkSettingsValues.fromRuntime();
                statusLine = "Settings refreshed.";
                warningLine = "";
                refreshUi();
            }
            case ACTION_LOAD_PRESET -> onLoadPreset(data);
            case ACTION_APPLY -> onApply(data);
            default -> {
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        playerRef.sendMessage(Message.raw("You can change these settings again any time with /tw settings."));
    }

    private void bindStaticEvents(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsRefreshButton",
                EventData.of(ACTION, ACTION_REFRESH),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsCloseButton",
                EventData.of(ACTION, ACTION_CLOSE),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsLoadPresetButton",
                appendFormEventData(EventData.of(ACTION, ACTION_LOAD_PRESET)),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsApplyButton",
                appendFormEventData(EventData.of(ACTION, ACTION_APPLY)),
                false
        );
    }

    @Nonnull
    private EventData appendFormEventData(@Nonnull EventData eventData) {
        return eventData
                .append(KEY_PRESET, "#TwSettingsPresetDropdown.Value")
                .append(KEY_POP_LIMIT, "#TwSettingsPopulationLimitInput.Value")
                .append(KEY_POP_SCOPE, "#TwSettingsPopulationScopeDropdown.Value")
                .append(KEY_SIMPLE_CLAIMS_ENABLED, "#TwSettingsSimpleClaimsEnabledCheck.Value")
                .append(KEY_CLAIM_LIMIT_CHUNK, "#TwSettingsClaimLimitChunkInput.Value")
                .append(KEY_CLAIM_LIMIT_TOTAL, "#TwSettingsClaimLimitTotalInput.Value")
                .append(KEY_BREEDING_REQUIRES_CLAIM, "#TwSettingsBreedingRequiresClaimCheck.Value")
                .append(KEY_SIMPLE_CLAIMS_PROTECT, "#TwSettingsSimpleClaimsProtectCheck.Value")
                .append(KEY_OWNERSHIP_BLOCK_OWNER_DAMAGE, "#TwSettingsBlockOwnerDamageCheck.Value")
                .append(KEY_OWNERSHIP_BLOCK_ALL_DAMAGE_IF_OWNED, "#TwSettingsBlockAllDamageIfOwnedCheck.Value")
                .append(KEY_OWNERSHIP_INVULNERABLE_IF_OWNED, "#TwSettingsInvulnerableIfOwnedCheck.Value")
                .append(KEY_CAPTURE_CLEARS_OWNER, "#TwSettingsCaptureClearsOwnerCheck.Value")
                .append(KEY_SPAWN_SETS_OWNER, "#TwSettingsSpawnSetsOwnerCheck.Value")
                .append(KEY_CAPTURE_REQUIRES_OWNER, "#TwSettingsCaptureRequiresOwnerCheck.Value")
                .append(KEY_SPAWN_REQUIRES_OWNER, "#TwSettingsSpawnRequiresOwnerCheck.Value")
                .append(KEY_INTERACTION_REQUIRES_OWNER, "#TwSettingsInteractionRequiresOwnerCheck.Value")
                .append(KEY_LINKING_REQUIRES_OWNER, "#TwSettingsLinkingRequiresOwnerCheck.Value")
                .append(KEY_NEEDS_ENABLED, "#TwSettingsNeedsEnabledCheck.Value")
                .append(KEY_NEEDS_DAMAGE_ENABLED, "#TwSettingsNeedsDamageEnabledCheck.Value")
                .append(KEY_NEEDS_TICK_POLICY_MODE, "#TwSettingsNeedsTickPolicyModeDropdown.Value")
                .append(KEY_NEEDS_OWNER_OFFLINE_GRACE_HOURS, "#TwSettingsNeedsOwnerOfflineGraceHoursInput.Value")
                .append(KEY_NEEDS_OWNER_OFFLINE_DECAY_MULTIPLIER, "#TwSettingsNeedsOwnerOfflineDecayMultiplierInput.Value")
                .append(KEY_NEEDS_DAMAGE_MODEL, "#TwSettingsNeedsDamageModelDropdown.Value")
                .append(KEY_NEEDS_DAMAGE_DUAL_NEED_RULE, "#TwSettingsNeedsDamageDualNeedRuleDropdown.Value")
                .append(KEY_NEEDS_STARVATION_DAMAGE_PER_MINUTE, "#TwSettingsNeedsStarvationDamagePerMinuteInput.Value")
                .append(KEY_NEEDS_DEHYDRATION_DAMAGE_PER_MINUTE, "#TwSettingsNeedsDehydrationDamagePerMinuteInput.Value")
                .append(KEY_NEEDS_DAMAGE_LETHAL, "#TwSettingsNeedsDamageLethalCheck.Value")
                .append(KEY_HAPPINESS_ENABLED, "#TwSettingsHappinessEnabledCheck.Value")
                .append(KEY_PASSIVE_BREEDING_ENABLED, "#TwSettingsPassiveBreedingEnabledCheck.Value")
                .append(KEY_BREEDING_REQUIRES_HAPPINESS, "#TwSettingsBreedingRequiresHappinessCheck.Value")
                .append(KEY_BREEDING_GENDER_ENABLED, "#TwSettingsBreedingGenderEnabledCheck.Value")
                .append(KEY_TRAITS_ENABLED, "#TwSettingsTraitsEnabledCheck.Value")
                .append(KEY_LEVELING_ENABLED, "#TwSettingsLevelingEnabledCheck.Value")
                .append(KEY_TALENTS_ENABLED, "#TwSettingsTalentsEnabledCheck.Value")
                .append(KEY_REVIVE_SYSTEM_ENABLED, "#TwSettingsReviveSystemEnabledCheck.Value")
                .append(KEY_RECALL_TELEPORTING_ENABLED, "#TwSettingsRecallTeleportingEnabledCheck.Value")
                .append(KEY_TELEMETRY_ENABLED, "#TwSettingsTelemetryEnabledCheck.Value")
                .append(KEY_TELEMETRY_BREADCRUMBS_ENABLED, "#TwSettingsTelemetryBreadcrumbsEnabledCheck.Value");
    }

    private void render(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.set("#TwSettingsStatusLine.Text", warningLine.isBlank() ? statusLine : warningLine);
        commandBuilder.set("#TwSettingsApplyButton.Text", applyInProgress ? "Applying..." : "Apply");
        commandBuilder.set("#TwSettingsPresetDropdown.Entries", TameworkSettingsPreset.dropdownEntries());
        commandBuilder.set("#TwSettingsPresetDropdown.Value", TameworkSettingsPreset.match(currentValues).value());
        commandBuilder.set("#TwSettingsPopulationLimitInput.Value", String.valueOf(currentValues.populationLimitPerPlayerOwnedTotal()));
        commandBuilder.set("#TwSettingsPopulationScopeDropdown.Entries", populationScopeEntries());
        commandBuilder.set("#TwSettingsPopulationScopeDropdown.Value", currentValues.populationPerPlayerLimitScope().configValue());
        commandBuilder.set("#TwSettingsSimpleClaimsEnabledCheck.Value", currentValues.simpleClaimsEnabled());
        commandBuilder.set("#TwSettingsClaimLimitChunkInput.Value", String.valueOf(currentValues.simpleClaimsLimitPerClaimChunk()));
        commandBuilder.set("#TwSettingsClaimLimitTotalInput.Value", String.valueOf(currentValues.simpleClaimsLimitPerClaimTotal()));
        commandBuilder.set("#TwSettingsBreedingRequiresClaimCheck.Value", currentValues.simpleClaimsBreedingRequiresClaim());
        commandBuilder.set("#TwSettingsSimpleClaimsProtectCheck.Value", currentValues.simpleClaimsProtectTamedFromNonMembers());
        commandBuilder.set("#TwSettingsBlockOwnerDamageCheck.Value", currentValues.blockOwnerDamage());
        commandBuilder.set("#TwSettingsBlockAllDamageIfOwnedCheck.Value", currentValues.blockAllPlayerDamageIfOwned());
        commandBuilder.set("#TwSettingsInvulnerableIfOwnedCheck.Value", currentValues.invulnerableIfOwned());
        commandBuilder.set("#TwSettingsCaptureClearsOwnerCheck.Value", currentValues.captureClearsOwner());
        commandBuilder.set("#TwSettingsSpawnSetsOwnerCheck.Value", currentValues.spawnSetsOwner());
        commandBuilder.set("#TwSettingsCaptureRequiresOwnerCheck.Value", currentValues.captureRequiresOwner());
        commandBuilder.set("#TwSettingsSpawnRequiresOwnerCheck.Value", currentValues.spawnRequiresOwner());
        commandBuilder.set("#TwSettingsInteractionRequiresOwnerCheck.Value", currentValues.interactionRequiresOwner());
        commandBuilder.set("#TwSettingsLinkingRequiresOwnerCheck.Value", currentValues.linkingRequiresOwner());
        commandBuilder.set("#TwSettingsNeedsEnabledCheck.Value", currentValues.needsEnabled());
        commandBuilder.set("#TwSettingsNeedsDamageEnabledCheck.Value", currentValues.needsDamageEnabled());
        commandBuilder.set("#TwSettingsNeedsTickPolicyModeDropdown.Entries", needsTickPolicyModeEntries());
        commandBuilder.set("#TwSettingsNeedsTickPolicyModeDropdown.Value", currentValues.needsTickPolicyMode().toConfigValue());
        commandBuilder.set("#TwSettingsNeedsOwnerOfflineGraceHoursInput.Value", String.valueOf(currentValues.needsOwnerOfflineGraceHours()));
        commandBuilder.set("#TwSettingsNeedsOwnerOfflineDecayMultiplierInput.Value", String.valueOf(currentValues.needsOwnerOfflineDecayMultiplier()));
        commandBuilder.set("#TwSettingsNeedsDamageModelDropdown.Entries", needsDamageModelEntries());
        commandBuilder.set("#TwSettingsNeedsDamageModelDropdown.Value", currentValues.needsDamageModel().toConfigValue());
        commandBuilder.set("#TwSettingsNeedsDamageDualNeedRuleDropdown.Entries", needsDamageDualNeedRuleEntries());
        commandBuilder.set("#TwSettingsNeedsDamageDualNeedRuleDropdown.Value", currentValues.needsDamageDualNeedRule().toConfigValue());
        commandBuilder.set("#TwSettingsNeedsStarvationDamagePerMinuteInput.Value", String.valueOf(currentValues.needsStarvationDamagePerMinute()));
        commandBuilder.set("#TwSettingsNeedsDehydrationDamagePerMinuteInput.Value", String.valueOf(currentValues.needsDehydrationDamagePerMinute()));
        commandBuilder.set("#TwSettingsNeedsDamageLethalCheck.Value", currentValues.needsDamageLethal());
        commandBuilder.set("#TwSettingsHappinessEnabledCheck.Value", currentValues.happinessEnabled());
        commandBuilder.set("#TwSettingsPassiveBreedingEnabledCheck.Value", currentValues.passiveBreedingEnabled());
        commandBuilder.set("#TwSettingsBreedingRequiresHappinessCheck.Value", currentValues.breedingRequiresHappiness());
        commandBuilder.set("#TwSettingsBreedingGenderEnabledCheck.Value", currentValues.breedingGenderEnabled());
        commandBuilder.set("#TwSettingsTraitsEnabledCheck.Value", currentValues.traitsEnabled());
        commandBuilder.set("#TwSettingsLevelingEnabledCheck.Value", currentValues.levelingEnabled());
        commandBuilder.set("#TwSettingsTalentsEnabledCheck.Value", currentValues.talentsEnabled());
        commandBuilder.set("#TwSettingsReviveSystemEnabledCheck.Value", currentValues.reviveSystemEnabled());
        commandBuilder.set("#TwSettingsRecallTeleportingEnabledCheck.Value", currentValues.recallTeleportingEnabled());
        commandBuilder.set("#TwSettingsTelemetryEnabledCheck.Value", currentValues.telemetryEnabled());
        commandBuilder.set("#TwSettingsTelemetryBreadcrumbsEnabledCheck.Value", currentValues.telemetryBreadcrumbsEnabled());
    }

    private void onApply(@Nonnull EventPayload payload) {
        if (applyInProgress) {
            warningLine = "Apply already in progress.";
            statusLine = "";
            refreshUi();
            return;
        }

        ParseResult parseResult = parseValues(payload);
        if (!parseResult.success()) {
            warningLine = parseResult.message();
            statusLine = "";
            refreshUi();
            return;
        }

        TameworkSettingsValues requested = parseResult.values();
        applyInProgress = true;
        statusLine = "Applying settings...";
        warningLine = "";
        refreshUi();

        CompletableFuture
                .supplyAsync(() -> applySettings(requested))
                .whenComplete((outcome, throwable) -> world.execute(() -> {
                    applyInProgress = false;
                    if (throwable != null) {
                        plugin.getLogger().at(Level.WARNING).withCause(throwable).log("Tamework settings apply failed.");
                        statusLine = "";
                        warningLine = "Failed to apply settings.";
                        refreshUi();
                        return;
                    }
                    currentValues = TameworkSettingsValues.fromRuntime();
                    if (outcome == null) {
                        statusLine = "";
                        warningLine = "Failed to apply settings.";
                    } else if (outcome.partial()) {
                        statusLine = outcome.message();
                        warningLine = outcome.warning();
                    } else if (outcome.success()) {
                        statusLine = outcome.message();
                        warningLine = "";
                    } else {
                        statusLine = "";
                        warningLine = outcome.warning();
                    }
                    refreshUi();
                }));
    }

    private void onLoadPreset(@Nonnull EventPayload payload) {
        TameworkSettingsPreset preset = TameworkSettingsPreset.fromConfigValue(payload.preset);
        if (!preset.isLoadable()) {
            statusLine = "Select a preset to load.";
            warningLine = "";
            refreshUi();
            return;
        }
        ParseResult parseResult = parseValues(payload);
        TameworkSettingsValues baseValues = parseResult.success() && parseResult.values() != null
                ? parseResult.values()
                : currentValues;
        currentValues = preset.applyTo(baseValues);
        statusLine = parseResult.success()
                ? "Loaded " + preset.displayName() + " preset. Review and click Apply to save."
                : "Loaded " + preset.displayName() + " preset. Invalid unsaved numeric inputs were discarded.";
        warningLine = "";
        refreshUi();
    }

    @Nonnull
    private ApplyOutcome applySettings(@Nonnull TameworkSettingsValues values) {
        Path globalSettingsPath = resolveSettingsDirectory().resolve(TameworkSettingsStore.GLOBAL_SETTINGS_FILE_NAME);
        TameworkSettingsStore.GlobalSettingsSnapshot snapshot = values.toGlobalSettingsSnapshot();
        if (!TameworkSettingsStore.saveGlobalSettings(globalSettingsPath, snapshot, plugin.getLogger())) {
            return ApplyOutcome.failure("Failed to save universe settings.");
        }

        String telemetryWarning = saveTelemetrySettings(values.telemetryEnabled(), values.telemetryBreadcrumbsEnabled());
        if (!telemetryWarning.isBlank()) {
            return ApplyOutcome.partial("Applied universe settings.", telemetryWarning);
        }
        return ApplyOutcome.success("Applied settings.");
    }

    @Nonnull
    private String saveTelemetrySettings(boolean enabled, boolean breadcrumbsEnabled) {
        try {
            CrashTelemetryService service = plugin.getCrashTelemetryService();
            if (service != null) {
                service.applyBreadcrumbsEnabledSetting(breadcrumbsEnabled);
                service.applyEnabledSetting(enabled);
            }
        } catch (Exception ex) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log("Failed applying crash telemetry setting at runtime.");
            return "Universe settings applied, but crash telemetry setting failed to apply at runtime.";
        }
        return "";
    }

    @Nonnull
    private Path resolveSettingsDirectory() {
        return TameworkSettingsStore.resolveSettingsDirectory(plugin);
    }

    @Nonnull
    private ParseResult parseValues(@Nonnull EventPayload payload) {
        Integer populationLimit = parseNonNegativeInt(payload.populationLimit, "Population limit");
        if (populationLimit == null) {
            return ParseResult.failure("Population limit must be a non-negative integer.");
        }
        Integer claimLimitChunk = parseNonNegativeInt(payload.claimLimitChunk, "SimpleClaims claim-chunk limit");
        if (claimLimitChunk == null) {
            return ParseResult.failure("SimpleClaims claim-chunk limit must be a non-negative integer.");
        }
        Integer claimLimitTotal = parseNonNegativeInt(payload.claimLimitTotal, "SimpleClaims claim-total limit");
        if (claimLimitTotal == null) {
            return ParseResult.failure("SimpleClaims claim-total limit must be a non-negative integer.");
        }
        Double needsOwnerOfflineGraceHours = parseNonNegativeDouble(
                payload.needsOwnerOfflineGraceHours,
                "Needs owner-offline grace hours"
        );
        if (needsOwnerOfflineGraceHours == null) {
            return ParseResult.failure("Needs owner-offline grace hours must be a non-negative number.");
        }
        Double needsOwnerOfflineDecayMultiplier = parseNonNegativeDouble(
                payload.needsOwnerOfflineDecayMultiplier,
                "Needs owner-offline decay multiplier"
        );
        if (needsOwnerOfflineDecayMultiplier == null) {
            return ParseResult.failure("Needs owner-offline decay multiplier must be a non-negative number.");
        }
        Double needsStarvationDamagePerMinute = parseNonNegativeDouble(
                payload.needsStarvationDamagePerMinute,
                "Needs starvation damage per minute"
        );
        if (needsStarvationDamagePerMinute == null) {
            return ParseResult.failure("Needs starvation damage per minute must be a non-negative number.");
        }
        Double needsDehydrationDamagePerMinute = parseNonNegativeDouble(
                payload.needsDehydrationDamagePerMinute,
                "Needs dehydration damage per minute"
        );
        if (needsDehydrationDamagePerMinute == null) {
            return ParseResult.failure("Needs dehydration damage per minute must be a non-negative number.");
        }

        TwGlobalConfig.PerPlayerLimitScope scope = TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(payload.populationScope);
        String tickPolicyModeValue = trim(payload.needsTickPolicyMode);
        if (tickPolicyModeValue.isBlank()) {
            tickPolicyModeValue = currentValues.needsTickPolicyMode().toConfigValue();
        }
        TwNeedsConfig.TickPolicyMode tickPolicyMode = TwNeedsConfig.TickPolicyMode.fromConfigValue(tickPolicyModeValue);
        String damageModelValue = trim(payload.needsDamageModel);
        if (damageModelValue.isBlank()) {
            damageModelValue = currentValues.needsDamageModel().toConfigValue();
        }
        TwNeedsConfig.DamageModel damageModel = TwNeedsConfig.DamageModel.fromConfigValue(damageModelValue);
        String damageDualNeedRuleValue = trim(payload.needsDamageDualNeedRule);
        if (damageDualNeedRuleValue.isBlank()) {
            damageDualNeedRuleValue = currentValues.needsDamageDualNeedRule().toConfigValue();
        }
        TwNeedsConfig.DualNeedRule damageDualNeedRule =
                TwNeedsConfig.DualNeedRule.fromConfigValue(damageDualNeedRuleValue);

        TameworkSettingsValues values = new TameworkSettingsValues(
                populationLimit,
                scope,
                boolOrDefault(payload.simpleClaimsEnabled, currentValues.simpleClaimsEnabled()),
                claimLimitChunk,
                claimLimitTotal,
                boolOrDefault(payload.breedingRequiresClaim, currentValues.simpleClaimsBreedingRequiresClaim()),
                boolOrDefault(payload.simpleClaimsProtect, currentValues.simpleClaimsProtectTamedFromNonMembers()),
                boolOrDefault(payload.blockOwnerDamage, currentValues.blockOwnerDamage()),
                boolOrDefault(payload.blockAllDamageIfOwned, currentValues.blockAllPlayerDamageIfOwned()),
                boolOrDefault(payload.invulnerableIfOwned, currentValues.invulnerableIfOwned()),
                boolOrDefault(payload.captureClearsOwner, currentValues.captureClearsOwner()),
                boolOrDefault(payload.spawnSetsOwner, currentValues.spawnSetsOwner()),
                boolOrDefault(payload.captureRequiresOwner, currentValues.captureRequiresOwner()),
                boolOrDefault(payload.spawnRequiresOwner, currentValues.spawnRequiresOwner()),
                boolOrDefault(payload.interactionRequiresOwner, currentValues.interactionRequiresOwner()),
                boolOrDefault(payload.linkingRequiresOwner, currentValues.linkingRequiresOwner()),
                boolOrDefault(payload.needsEnabled, currentValues.needsEnabled()),
                boolOrDefault(payload.needsDamageEnabled, currentValues.needsDamageEnabled()),
                tickPolicyMode,
                needsOwnerOfflineGraceHours,
                needsOwnerOfflineDecayMultiplier,
                damageModel,
                damageDualNeedRule,
                needsStarvationDamagePerMinute,
                needsDehydrationDamagePerMinute,
                boolOrDefault(payload.needsDamageLethal, currentValues.needsDamageLethal()),
                boolOrDefault(payload.happinessEnabled, currentValues.happinessEnabled()),
                boolOrDefault(payload.passiveBreedingEnabled, currentValues.passiveBreedingEnabled()),
                boolOrDefault(payload.breedingRequiresHappiness, currentValues.breedingRequiresHappiness()),
                boolOrDefault(payload.breedingGenderEnabled, currentValues.breedingGenderEnabled()),
                boolOrDefault(payload.traitsEnabled, currentValues.traitsEnabled()),
                boolOrDefault(payload.levelingEnabled, currentValues.levelingEnabled()),
                boolOrDefault(payload.talentsEnabled, currentValues.talentsEnabled()),
                boolOrDefault(payload.reviveSystemEnabled, currentValues.reviveSystemEnabled()),
                boolOrDefault(payload.recallTeleportingEnabled, currentValues.recallTeleportingEnabled()),
                boolOrDefault(payload.telemetryEnabled, currentValues.telemetryEnabled()),
                boolOrDefault(payload.telemetryBreadcrumbsEnabled, currentValues.telemetryBreadcrumbsEnabled())
        );
        return ParseResult.success(values);
    }

    @Nullable
    private Integer parseNonNegativeInt(@Nullable String raw, @Nonnull String label) {
        String value = trim(raw);
        if (value.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(0, parsed);
        } catch (NumberFormatException ignored) {
            plugin.getLogger().at(Level.FINE).log("Invalid integer input for " + label + ": " + value);
            return null;
        }
    }

    @Nullable
    private Double parseNonNegativeDouble(@Nullable String raw, @Nonnull String label) {
        String value = trim(raw);
        if (value.isBlank()) {
            return 0.0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                plugin.getLogger().at(Level.FINE).log("Invalid decimal input for " + label + ": " + value);
                return null;
            }
            return Math.max(0.0, parsed);
        } catch (NumberFormatException ignored) {
            plugin.getLogger().at(Level.FINE).log("Invalid decimal input for " + label + ": " + value);
            return null;
        }
    }

    private boolean boolOrDefault(@Nullable Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    private void refreshUi() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        render(commandBuilder);
        bindStaticEvents(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private List<DropdownEntryInfo> populationScopeEntries() {
        return List.of(
                new DropdownEntryInfo(LocalizableString.fromString("Per World"), TwGlobalConfig.PerPlayerLimitScope.PER_WORLD.configValue()),
                new DropdownEntryInfo(LocalizableString.fromString("Global"), TwGlobalConfig.PerPlayerLimitScope.GLOBAL.configValue())
        );
    }

    private List<DropdownEntryInfo> needsTickPolicyModeEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString("Owner Online Grace Then Decay"),
                        TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString("Any Loaded Player"),
                        TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER.toConfigValue()
                )
        );
    }

    private List<DropdownEntryInfo> needsDamageModelEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString("Min Only Percent"),
                        TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString("Min Only Flat"),
                        TwNeedsConfig.DamageModel.MIN_ONLY_FLAT.toConfigValue()
                )
        );
    }

    private List<DropdownEntryInfo> needsDamageDualNeedRuleEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString("Use Higher Only"),
                        TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString("Sum Both"),
                        TwNeedsConfig.DualNeedRule.SUM_BOTH.toConfigValue()
                )
        );
    }

    @Nonnull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private record ParseResult(boolean success, @Nonnull String message, @Nullable TameworkSettingsValues values) {
        static ParseResult success(@Nonnull TameworkSettingsValues values) {
            return new ParseResult(true, "", values);
        }

        static ParseResult failure(@Nonnull String message) {
            return new ParseResult(false, message, null);
        }
    }

    private record ApplyOutcome(boolean success, boolean partial, @Nonnull String message, @Nonnull String warning) {
        static ApplyOutcome success(@Nonnull String message) {
            return new ApplyOutcome(true, false, message, "");
        }

        static ApplyOutcome partial(@Nonnull String message, @Nonnull String warning) {
            return new ApplyOutcome(true, true, message, warning);
        }

        static ApplyOutcome failure(@Nonnull String warning) {
            return new ApplyOutcome(false, false, "", warning);
        }
    }

    /** Event payload for settings page actions. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action).add()
                .<String>append(new KeyedCodec<>(KEY_PRESET, Codec.STRING), (x, v) -> x.preset = v, x -> x.preset).add()
                .<String>append(new KeyedCodec<>(KEY_POP_LIMIT, Codec.STRING), (x, v) -> x.populationLimit = v, x -> x.populationLimit).add()
                .<String>append(new KeyedCodec<>(KEY_POP_SCOPE, Codec.STRING), (x, v) -> x.populationScope = v, x -> x.populationScope).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SIMPLE_CLAIMS_ENABLED, Codec.BOOLEAN), (x, v) -> x.simpleClaimsEnabled = v, x -> x.simpleClaimsEnabled).add()
                .<String>append(new KeyedCodec<>(KEY_CLAIM_LIMIT_CHUNK, Codec.STRING), (x, v) -> x.claimLimitChunk = v, x -> x.claimLimitChunk).add()
                .<String>append(new KeyedCodec<>(KEY_CLAIM_LIMIT_TOTAL, Codec.STRING), (x, v) -> x.claimLimitTotal = v, x -> x.claimLimitTotal).add()
                .<Boolean>append(new KeyedCodec<>(KEY_BREEDING_REQUIRES_CLAIM, Codec.BOOLEAN), (x, v) -> x.breedingRequiresClaim = v, x -> x.breedingRequiresClaim).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SIMPLE_CLAIMS_PROTECT, Codec.BOOLEAN), (x, v) -> x.simpleClaimsProtect = v, x -> x.simpleClaimsProtect).add()
                .<Boolean>append(new KeyedCodec<>(KEY_OWNERSHIP_BLOCK_OWNER_DAMAGE, Codec.BOOLEAN), (x, v) -> x.blockOwnerDamage = v, x -> x.blockOwnerDamage).add()
                .<Boolean>append(new KeyedCodec<>(KEY_OWNERSHIP_BLOCK_ALL_DAMAGE_IF_OWNED, Codec.BOOLEAN), (x, v) -> x.blockAllDamageIfOwned = v, x -> x.blockAllDamageIfOwned).add()
                .<Boolean>append(new KeyedCodec<>(KEY_OWNERSHIP_INVULNERABLE_IF_OWNED, Codec.BOOLEAN), (x, v) -> x.invulnerableIfOwned = v, x -> x.invulnerableIfOwned).add()
                .<Boolean>append(new KeyedCodec<>(KEY_CAPTURE_CLEARS_OWNER, Codec.BOOLEAN), (x, v) -> x.captureClearsOwner = v, x -> x.captureClearsOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SPAWN_SETS_OWNER, Codec.BOOLEAN), (x, v) -> x.spawnSetsOwner = v, x -> x.spawnSetsOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_CAPTURE_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.captureRequiresOwner = v, x -> x.captureRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SPAWN_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.spawnRequiresOwner = v, x -> x.spawnRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_INTERACTION_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.interactionRequiresOwner = v, x -> x.interactionRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_LINKING_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.linkingRequiresOwner = v, x -> x.linkingRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NEEDS_ENABLED, Codec.BOOLEAN), (x, v) -> x.needsEnabled = v, x -> x.needsEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_ENABLED, Codec.BOOLEAN), (x, v) -> x.needsDamageEnabled = v, x -> x.needsDamageEnabled).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_TICK_POLICY_MODE, Codec.STRING), (x, v) -> x.needsTickPolicyMode = v, x -> x.needsTickPolicyMode).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_OWNER_OFFLINE_GRACE_HOURS, Codec.STRING), (x, v) -> x.needsOwnerOfflineGraceHours = v, x -> x.needsOwnerOfflineGraceHours).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_OWNER_OFFLINE_DECAY_MULTIPLIER, Codec.STRING), (x, v) -> x.needsOwnerOfflineDecayMultiplier = v, x -> x.needsOwnerOfflineDecayMultiplier).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_MODEL, Codec.STRING), (x, v) -> x.needsDamageModel = v, x -> x.needsDamageModel).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_DUAL_NEED_RULE, Codec.STRING), (x, v) -> x.needsDamageDualNeedRule = v, x -> x.needsDamageDualNeedRule).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_STARVATION_DAMAGE_PER_MINUTE, Codec.STRING), (x, v) -> x.needsStarvationDamagePerMinute = v, x -> x.needsStarvationDamagePerMinute).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_DEHYDRATION_DAMAGE_PER_MINUTE, Codec.STRING), (x, v) -> x.needsDehydrationDamagePerMinute = v, x -> x.needsDehydrationDamagePerMinute).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_LETHAL, Codec.BOOLEAN), (x, v) -> x.needsDamageLethal = v, x -> x.needsDamageLethal).add()
                .<Boolean>append(new KeyedCodec<>(KEY_HAPPINESS_ENABLED, Codec.BOOLEAN), (x, v) -> x.happinessEnabled = v, x -> x.happinessEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_PASSIVE_BREEDING_ENABLED, Codec.BOOLEAN), (x, v) -> x.passiveBreedingEnabled = v, x -> x.passiveBreedingEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_BREEDING_REQUIRES_HAPPINESS, Codec.BOOLEAN), (x, v) -> x.breedingRequiresHappiness = v, x -> x.breedingRequiresHappiness).add()
                .<Boolean>append(new KeyedCodec<>(KEY_BREEDING_GENDER_ENABLED, Codec.BOOLEAN), (x, v) -> x.breedingGenderEnabled = v, x -> x.breedingGenderEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_TRAITS_ENABLED, Codec.BOOLEAN), (x, v) -> x.traitsEnabled = v, x -> x.traitsEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_LEVELING_ENABLED, Codec.BOOLEAN), (x, v) -> x.levelingEnabled = v, x -> x.levelingEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_TALENTS_ENABLED, Codec.BOOLEAN), (x, v) -> x.talentsEnabled = v, x -> x.talentsEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_REVIVE_SYSTEM_ENABLED, Codec.BOOLEAN), (x, v) -> x.reviveSystemEnabled = v, x -> x.reviveSystemEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_RECALL_TELEPORTING_ENABLED, Codec.BOOLEAN), (x, v) -> x.recallTeleportingEnabled = v, x -> x.recallTeleportingEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_TELEMETRY_ENABLED, Codec.BOOLEAN), (x, v) -> x.telemetryEnabled = v, x -> x.telemetryEnabled).add()
                .<Boolean>append(
                        new KeyedCodec<>(KEY_TELEMETRY_BREADCRUMBS_ENABLED, Codec.BOOLEAN),
                        (x, v) -> x.telemetryBreadcrumbsEnabled = v,
                        x -> x.telemetryBreadcrumbsEnabled
                )
                .add()
                .build();

        private String action;
        private String preset;
        private String populationLimit;
        private String populationScope;
        private Boolean simpleClaimsEnabled;
        private String claimLimitChunk;
        private String claimLimitTotal;
        private Boolean breedingRequiresClaim;
        private Boolean simpleClaimsProtect;
        private Boolean blockOwnerDamage;
        private Boolean blockAllDamageIfOwned;
        private Boolean invulnerableIfOwned;
        private Boolean captureClearsOwner;
        private Boolean spawnSetsOwner;
        private Boolean captureRequiresOwner;
        private Boolean spawnRequiresOwner;
        private Boolean interactionRequiresOwner;
        private Boolean linkingRequiresOwner;
        private Boolean needsEnabled;
        private Boolean needsDamageEnabled;
        private String needsTickPolicyMode;
        private String needsOwnerOfflineGraceHours;
        private String needsOwnerOfflineDecayMultiplier;
        private String needsDamageModel;
        private String needsDamageDualNeedRule;
        private String needsStarvationDamagePerMinute;
        private String needsDehydrationDamagePerMinute;
        private Boolean needsDamageLethal;
        private Boolean happinessEnabled;
        private Boolean passiveBreedingEnabled;
        private Boolean breedingRequiresHappiness;
        private Boolean breedingGenderEnabled;
        private Boolean traitsEnabled;
        private Boolean levelingEnabled;
        private Boolean talentsEnabled;
        private Boolean reviveSystemEnabled;
        private Boolean recallTeleportingEnabled;
        private Boolean telemetryEnabled;
        private Boolean telemetryBreadcrumbsEnabled;
    }
}
