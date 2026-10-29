package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.settings.AnimalAgingMode;
import com.alechilles.alecstamework.settings.CaptureItemOwnershipMode;
import com.alechilles.alecstamework.settings.DeployPlaces;
import com.alechilles.alecstamework.settings.DeployPlacesStore;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierRefreshService;
import com.alechilles.alecstamework.settings.NameFilterSettings;
import com.alechilles.alecstamework.settings.CompanionHudDefaultsStore;
import com.alechilles.alecstamework.settings.NameFilterSettingsStore;
import com.alechilles.alecstamework.settings.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.TameworkSettingsAnnouncementStore;
import com.alechilles.alecstamework.settings.NeedsResourceMode;
import com.alechilles.alecstamework.settings.mods.ModSettingsService;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Curated /tw settings page for common global settings. The General tab holds Tamework's own settings;
 * the Mod Settings tab ({@link TameworkSettingsModTab}) holds settings that installed mods declare.
 * Edits on both tabs wait for Apply, which saves them together.
 */
public final class TameworkSettingsPage extends InteractiveCustomUIPage<TameworkSettingsPage.EventPayload> {
    public static final String UI_PATH = "TameworkSettingsPage.ui";

    private static final String ACTION = "Action";
    private static final String ACTION_REFRESH = "Refresh";
    private static final String ACTION_APPLY = "Apply";
    private static final String ACTION_LOAD_PRESET = "LoadPreset";
    private static final String ACTION_CLOSE = "Close";
    private static final String ACTION_EDIT = "Edit";
    private static final String ACTION_EDIT_HUD_DEFAULT = "EditHudDefault";
    private static final String ACTION_SELECT_TAB = "SelectTab";
    private static final String KEY_TAB = "Tab";
    private static final String TAB_GENERAL = "General";
    private static final String TAB_MOD_SETTINGS = "ModSettings";
    private static final String SLATE_STYLES = "TameworkSlateStyles.ui";

    private static final String KEY_ANNOUNCEMENTS_ENABLED = "@AnnouncementsEnabled";
    private static final String KEY_NAME_FILTER_ENABLED = "@NameFilterEnabled";
    private static final String KEY_NAME_FILTER_USE_STOCK_LIST = "@NameFilterUseStockList";
    private static final String KEY_NAME_FILTER_BLOCKED_WORDS = "@NameFilterBlockedWords";
    private static final String KEY_NAME_FILTER_ALLOWED_WORDS = "@NameFilterAllowedWords";
    private static final String KEY_DEPLOY_MODE = "@DeployMode";
    private static final String KEY_DEPLOY_PLACES = "@DeployPlaces";
    private static final String KEY_PRESET = "@Preset";
    private static final String KEY_POP_LIMIT = "@PopulationLimit";
    private static final String KEY_POP_DEPLOYED_LIMIT = "@PopulationDeployedLimit";
    private static final String KEY_POP_SCOPE = "@PopulationScope";
    private static final String KEY_COMMAND_PANEL_CARDS_PER_PAGE = "@CommandPanelCardsPerPage";
    private static final String KEY_SIMPLE_CLAIMS_ENABLED = "@SimpleClaimsEnabled";
    private static final String KEY_CLAIM_LIMIT_CHUNK = "@ClaimLimitChunk";
    private static final String KEY_CLAIM_LIMIT_TOTAL = "@ClaimLimitTotal";
    private static final String KEY_BREEDING_REQUIRES_CLAIM = "@BreedingRequiresClaim";
    private static final String KEY_SIMPLE_CLAIMS_PROTECT = "@SimpleClaimsProtect";
    private static final String KEY_OWNERSHIP_BLOCK_OWNER_DAMAGE = "@OwnershipBlockOwnerDamage";
    private static final String KEY_OWNERSHIP_BLOCK_ALL_DAMAGE_IF_OWNED = "@OwnershipBlockAllDamageIfOwned";
    private static final String KEY_OWNERSHIP_INVULNERABLE_IF_OWNED = "@OwnershipInvulnerableIfOwned";
    private static final String KEY_CAPTURE_ITEM_OWNERSHIP = "@CaptureItemOwnership";
    private static final String KEY_CAPTURE_REQUIRES_OWNER = "@CaptureRequiresOwner";
    private static final String KEY_INTERACTION_REQUIRES_OWNER = "@InteractionRequiresOwner";
    private static final String KEY_LINKING_REQUIRES_OWNER = "@LinkingRequiresOwner";
    private static final String KEY_NEEDS_ENABLED = "@NeedsEnabled";
    private static final String KEY_NEEDS_RESOURCE_MODE = "@NeedsResourceMode";
    private static final String KEY_NEEDS_DAMAGE_ENABLED = "@NeedsDamageEnabled";
    private static final String KEY_NEEDS_TICK_POLICY_MODE = "@NeedsTickPolicyMode";
    private static final String KEY_NEEDS_OWNER_OFFLINE_GRACE_HOURS = "@NeedsOwnerOfflineGraceHours";
    private static final String KEY_NEEDS_OWNER_OFFLINE_DECAY_MULTIPLIER = "@NeedsOwnerOfflineDecayMultiplier";
    private static final String KEY_NEEDS_DAMAGE_MODEL = "@NeedsDamageModel";
    private static final String KEY_NEEDS_DAMAGE_DUAL_NEED_RULE = "@NeedsDamageDualNeedRule";
    private static final String KEY_NEEDS_STARVATION_DAMAGE_PER_MINUTE = "@NeedsStarvationDamagePerMinute";
    private static final String KEY_NEEDS_DEHYDRATION_DAMAGE_PER_MINUTE = "@NeedsDehydrationDamagePerMinute";
    private static final String KEY_NEEDS_DAMAGE_LETHAL = "@NeedsDamageLethal";
    private static final String KEY_ANIMAL_AGING_MODE = "@AnimalAgingMode";
    private static final String KEY_ANIMAL_OLD_AGE_DEATH = "@AnimalOldAgeDeath";
    private static final String KEY_HAPPINESS_ENABLED = "@HappinessEnabled";
    private static final String KEY_PASSIVE_BREEDING_ENABLED = "@PassiveBreedingEnabled";
    private static final String KEY_BREEDING_REQUIRES_HAPPINESS = "@BreedingRequiresHappiness";
    private static final String KEY_BREEDING_GENDER_ENABLED = "@BreedingGenderEnabled";
    private static final String KEY_TRAITS_ENABLED = "@TraitsEnabled";
    private static final String KEY_LEVELING_ENABLED = "@LevelingEnabled";
    private static final String KEY_TALENTS_ENABLED = "@TalentsEnabled";
    private static final String KEY_REVIVE_SYSTEM_ENABLED = "@ReviveSystemEnabled";
    private static final String KEY_RECALL_TELEPORTING_ENABLED = "@RecallTeleportingEnabled";
    private static final String KEY_FLIGHT_UNLIMITED_VIGOUR = "@AvatarFlightUnlimitedVigour";

    private final Tamework plugin;
    private final World world;
    private final TameworkSettingsFormParser formParser;
    /** Settings that other mods declare; their edits wait for Apply like the rest of the page. */
    private final TameworkSettingsModTab modTab;

    private TameworkSettingsValues currentValues;
    private TameworkSettingsValues savedValues;
    /** The name filter is saved to its own file, so it is tracked beside the main values. */
    private NameFilterSettings currentNameFilter;
    private NameFilterSettings savedNameFilter;
    /** Where companions may come out, saved to its own file like the name filter. */
    private DeployPlaces currentDeploy;
    private DeployPlaces savedDeploy;
    private EventPayload draftPayload;
    private boolean unsavedChanges;
    private String statusLine = "";
    private String warningLine = "";
    private boolean applyInProgress;
    /** Set when this page hands over to the HUD default editor, which returns here; skips the leaving reminder. */
    private boolean openingHudDefaultEditor;
    private boolean modSettingsTabSelected;

    public TameworkSettingsPage(@Nonnull PlayerRef playerRef,
                                @Nonnull Tamework plugin,
                                @Nonnull World world) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.formParser = new TameworkSettingsFormParser(playerRef, plugin.getLogger());
        this.modTab = new TameworkSettingsModTab(playerRef, plugin);
        loadCurrentValues();
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
            modTab.render(commandBuilder, eventBuilder);
        } catch (Throwable throwable) {
            plugin.getLogger().at(Level.WARNING).withCause(throwable)
                    .log("Failed to build Tamework settings page.");
            try {
                plugin.getTelemetryEvents().recordError(
                        "ui_page_build_failed", throwable,
                        TameworkTelemetryContext.uiPage("TameworkSettingsPage", "settings_ui", "build",
                                "Failed to build Tamework settings page.").build());
            } catch (RuntimeException | LinkageError telemetryFailure) {
                // Preserve the page failure even when optional telemetry is unavailable.
            }
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
                loadCurrentValues();
                statusLine = resolveText("tamework.ui.settings.status.refreshed");
                refreshUi();
            }
            case ACTION_EDIT -> {
                draftPayload = data;
                warningLine = "";
                updateUnsavedChanges();
                refreshFeedback();
            }
            case ACTION_LOAD_PRESET -> onLoadPreset(data);
            case ACTION_APPLY -> onApply(data, store);
            case ACTION_EDIT_HUD_DEFAULT -> onEditHudDefault(data, ref, store);
            case ACTION_SELECT_TAB -> onSelectTab(data);
            case TameworkSettingsModTab.ACTION_BOOL, TameworkSettingsModTab.ACTION_CHOICE,
                 TameworkSettingsModTab.ACTION_TEXT, TameworkSettingsModTab.ACTION_RESET -> onModSettingEdit(action, data);
            default -> {
            }
        }
    }

    private void onSelectTab(@Nonnull EventPayload payload) {
        modSettingsTabSelected = TAB_MOD_SETTINGS.equals(trim(payload.tab));
        UICommandBuilder commandBuilder = new UICommandBuilder();
        renderTabs(commandBuilder);
        sendUpdate(commandBuilder, null, false);
    }

    private void onModSettingEdit(@Nonnull String action, @Nonnull EventPayload payload) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        if (!modTab.onEvent(action, payload.modSettingId, payload.modSettingBool, payload.modSettingValue, commandBuilder)) {
            return;
        }
        warningLine = "";
        updateUnsavedChanges();
        appendFeedback(commandBuilder);
        sendUpdate(commandBuilder, null, false);
    }

    private void renderTabs(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.set("#TwSettingsGeneralPanel.Visible", !modSettingsTabSelected);
        commandBuilder.set("#TwModSettingsPanel.Visible", modSettingsTabSelected);
        commandBuilder.set("#TwSettingsGeneralTab.Style", Value.ref(SLATE_STYLES,
                modSettingsTabSelected ? "CompanionTabButton" : "CompanionTabButtonSelected"));
        commandBuilder.set("#TwSettingsModSettingsTab.Style", Value.ref(SLATE_STYLES,
                modSettingsTabSelected ? "CompanionTabButtonSelected" : "CompanionTabButton"));
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (openingHudDefaultEditor) {
            return;
        }
        playerRef.sendMessage(Message.raw(resolveText("tamework.ui.settings.dismissReminder")));
    }

    private void bindStaticEvents(@Nonnull UIEventBuilder eventBuilder) {
        EventData editEvent = appendFormEventData(EventData.of(ACTION, ACTION_EDIT));
        for (var binding : editEvent.events().entrySet()) {
            if (ACTION.equals(binding.getKey()) || KEY_PRESET.equals(binding.getKey())) continue;
            String valueSelector = binding.getValue();
            String selector = valueSelector.substring(0, valueSelector.length() - ".Value".length());
            eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, selector, editEvent, false);
        }
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsGeneralTab",
                EventData.of(ACTION, ACTION_SELECT_TAB).append(KEY_TAB, TAB_GENERAL),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsModSettingsTab",
                EventData.of(ACTION, ACTION_SELECT_TAB).append(KEY_TAB, TAB_MOD_SETTINGS),
                false
        );
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
                CustomUIEventBindingType.ValueChanged,
                "#TwSettingsPresetDropdown",
                appendFormEventData(EventData.of(ACTION, ACTION_LOAD_PRESET)),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TwSettingsHudDefaultEditButton",
                appendFormEventData(EventData.of(ACTION, ACTION_EDIT_HUD_DEFAULT)),
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
                .append(KEY_POP_DEPLOYED_LIMIT, "#TwSettingsPopulationDeployedLimitInput.Value")
                .append(KEY_POP_SCOPE, "#TwSettingsPopulationScopeDropdown.Value")
                .append(KEY_COMMAND_PANEL_CARDS_PER_PAGE, "#TwSettingsCommandPanelCardsPerPageInput.Value")
                .append(KEY_SIMPLE_CLAIMS_ENABLED, "#TwSettingsSimpleClaimsEnabledCheck.Value")
                .append(KEY_CLAIM_LIMIT_CHUNK, "#TwSettingsClaimLimitChunkInput.Value")
                .append(KEY_CLAIM_LIMIT_TOTAL, "#TwSettingsClaimLimitTotalInput.Value")
                .append(KEY_BREEDING_REQUIRES_CLAIM, "#TwSettingsBreedingRequiresClaimCheck.Value")
                .append(KEY_SIMPLE_CLAIMS_PROTECT, "#TwSettingsSimpleClaimsProtectCheck.Value")
                .append(KEY_OWNERSHIP_BLOCK_OWNER_DAMAGE, "#TwSettingsBlockOwnerDamageCheck.Value")
                .append(KEY_OWNERSHIP_BLOCK_ALL_DAMAGE_IF_OWNED, "#TwSettingsBlockAllDamageIfOwnedCheck.Value")
                .append(KEY_OWNERSHIP_INVULNERABLE_IF_OWNED, "#TwSettingsInvulnerableIfOwnedCheck.Value")
                .append(KEY_CAPTURE_ITEM_OWNERSHIP, "#TwSettingsCaptureItemOwnershipDropdown.Value")
                .append(KEY_CAPTURE_REQUIRES_OWNER, "#TwSettingsCaptureRequiresOwnerCheck.Value")
                .append(KEY_INTERACTION_REQUIRES_OWNER, "#TwSettingsInteractionRequiresOwnerCheck.Value")
                .append(KEY_LINKING_REQUIRES_OWNER, "#TwSettingsLinkingRequiresOwnerCheck.Value")
                .append(KEY_NEEDS_ENABLED, "#TwSettingsNeedsEnabledCheck.Value")
                .append(KEY_NEEDS_RESOURCE_MODE, "#TwSettingsNeedsResourceModeDropdown.Value")
                .append(KEY_NEEDS_DAMAGE_ENABLED, "#TwSettingsNeedsDamageEnabledCheck.Value")
                .append(KEY_NEEDS_TICK_POLICY_MODE, "#TwSettingsNeedsTickPolicyModeDropdown.Value")
                .append(KEY_NEEDS_OWNER_OFFLINE_GRACE_HOURS, "#TwSettingsNeedsOwnerOfflineGraceHoursInput.Value")
                .append(KEY_NEEDS_OWNER_OFFLINE_DECAY_MULTIPLIER, "#TwSettingsNeedsOwnerOfflineDecayMultiplierInput.Value")
                .append(KEY_NEEDS_DAMAGE_MODEL, "#TwSettingsNeedsDamageModelDropdown.Value")
                .append(KEY_NEEDS_DAMAGE_DUAL_NEED_RULE, "#TwSettingsNeedsDamageDualNeedRuleDropdown.Value")
                .append(KEY_NEEDS_STARVATION_DAMAGE_PER_MINUTE, "#TwSettingsNeedsStarvationDamagePerMinuteInput.Value")
                .append(KEY_NEEDS_DEHYDRATION_DAMAGE_PER_MINUTE, "#TwSettingsNeedsDehydrationDamagePerMinuteInput.Value")
                .append(KEY_NEEDS_DAMAGE_LETHAL, "#TwSettingsNeedsDamageLethalCheck.Value")
                .append(KEY_ANIMAL_AGING_MODE, "#TwSettingsAnimalAgingModeDropdown.Value")
                .append(KEY_ANIMAL_OLD_AGE_DEATH, "#TwSettingsAnimalOldAgeDeathCheck.Value")
                .append(KEY_HAPPINESS_ENABLED, "#TwSettingsHappinessEnabledCheck.Value")
                .append(KEY_PASSIVE_BREEDING_ENABLED, "#TwSettingsPassiveBreedingEnabledCheck.Value")
                .append(KEY_BREEDING_REQUIRES_HAPPINESS, "#TwSettingsBreedingRequiresHappinessCheck.Value")
                .append(KEY_BREEDING_GENDER_ENABLED, "#TwSettingsBreedingGenderEnabledCheck.Value")
                .append(KEY_TRAITS_ENABLED, "#TwSettingsTraitsEnabledCheck.Value")
                .append(KEY_LEVELING_ENABLED, "#TwSettingsLevelingEnabledCheck.Value")
                .append(KEY_TALENTS_ENABLED, "#TwSettingsTalentsEnabledCheck.Value")
                .append(KEY_REVIVE_SYSTEM_ENABLED, "#TwSettingsReviveSystemEnabledCheck.Value")
                .append(KEY_RECALL_TELEPORTING_ENABLED, "#TwSettingsRecallTeleportingEnabledCheck.Value")
                .append(KEY_FLIGHT_UNLIMITED_VIGOUR, "#TwSettingsFlightUnlimitedVigourCheck.Value")
                .append(KEY_ANNOUNCEMENTS_ENABLED, "#TwSettingsAnnouncementsEnabledCheck.Value")
                .append(KEY_NAME_FILTER_ENABLED, "#TwSettingsNameFilterEnabledCheck.Value")
                .append(KEY_NAME_FILTER_USE_STOCK_LIST, "#TwSettingsNameFilterUseStockListCheck.Value")
                .append(KEY_NAME_FILTER_BLOCKED_WORDS, "#TwSettingsNameFilterBlockedWordsInput.Value")
                .append(KEY_NAME_FILTER_ALLOWED_WORDS, "#TwSettingsNameFilterAllowedWordsInput.Value")
                .append(KEY_DEPLOY_MODE, "#TwSettingsDeployModeDropdown.Value")
                .append(KEY_DEPLOY_PLACES, "#TwSettingsDeployPlacesInput.Value");
    }

    private void render(@Nonnull UICommandBuilder commandBuilder) {
        TameworkSettingsPageTextBinder.bindStaticText(commandBuilder, playerRef);
        renderTabs(commandBuilder);
        CompanionHudPlacement hudDefault = CompanionHudDefaultsStore.current();
        commandBuilder.set("#TwSettingsHudDefaultValue.Text", LocalizedText.format(playerRef,
                "tamework.ui.hudPlacement.summary",
                LocalizedText.resolve(playerRef, "tamework.ui.hudPlacement.point." + hudDefault.point().key()),
                hudDefault.x(), hudDefault.y()));
        renderFeedback(commandBuilder);
        commandBuilder.set("#TwSettingsPresetDropdown.Entries", TameworkSettingsPreset.dropdownEntries(resolveLanguage()));
        commandBuilder.set("#TwSettingsPresetDropdown.Value", TameworkSettingsPreset.match(currentValues).value());
        commandBuilder.set("#TwSettingsPopulationLimitInput.Value", String.valueOf(currentValues.populationLimitPerPlayerOwnedTotal()));
        commandBuilder.set("#TwSettingsPopulationDeployedLimitInput.Value",
                String.valueOf(currentValues.populationLimitPerPlayerDeployedTotal()));
        commandBuilder.set("#TwSettingsPopulationScopeDropdown.Entries", populationScopeEntries());
        commandBuilder.set("#TwSettingsPopulationScopeDropdown.Value", currentValues.populationPerPlayerLimitScope().configValue());
        commandBuilder.set("#TwSettingsCommandPanelCardsPerPageInput.Value", String.valueOf(currentValues.commandPanelCardsPerPage()));
        commandBuilder.set("#TwSettingsSimpleClaimsEnabledCheck.Value", currentValues.simpleClaimsEnabled());
        commandBuilder.set("#TwSettingsClaimLimitChunkInput.Value", String.valueOf(currentValues.simpleClaimsLimitPerClaimChunk()));
        commandBuilder.set("#TwSettingsClaimLimitTotalInput.Value", String.valueOf(currentValues.simpleClaimsLimitPerClaimTotal()));
        commandBuilder.set("#TwSettingsBreedingRequiresClaimCheck.Value", currentValues.simpleClaimsBreedingRequiresClaim());
        commandBuilder.set("#TwSettingsSimpleClaimsProtectCheck.Value", currentValues.simpleClaimsProtectTamedFromNonMembers());
        commandBuilder.set("#TwSettingsBlockOwnerDamageCheck.Value", currentValues.blockOwnerDamage());
        commandBuilder.set("#TwSettingsBlockAllDamageIfOwnedCheck.Value", currentValues.blockAllPlayerDamageIfOwned());
        commandBuilder.set("#TwSettingsInvulnerableIfOwnedCheck.Value", currentValues.invulnerableIfOwned());
        commandBuilder.set("#TwSettingsCaptureItemOwnershipDropdown.Entries", captureItemOwnershipEntries());
        commandBuilder.set("#TwSettingsCaptureItemOwnershipDropdown.Value",
                currentValues.captureItemOwnership().toConfigValue());
        renderCaptureItemOwnershipNote(commandBuilder, currentValues.captureItemOwnership());
        commandBuilder.set("#TwSettingsCaptureRequiresOwnerCheck.Value", currentValues.captureRequiresOwner());
        commandBuilder.set("#TwSettingsInteractionRequiresOwnerCheck.Value", currentValues.interactionRequiresOwner());
        commandBuilder.set("#TwSettingsLinkingRequiresOwnerCheck.Value", currentValues.linkingRequiresOwner());
        commandBuilder.set("#TwSettingsNeedsEnabledCheck.Value", currentValues.needsEnabled());
        commandBuilder.set("#TwSettingsNeedsResourceModeDropdown.Entries", needsResourceModeEntries());
        commandBuilder.set("#TwSettingsNeedsResourceModeDropdown.Value", currentValues.needsResourceMode());
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
        commandBuilder.set("#TwSettingsAnimalAgingModeDropdown.Entries", animalAgingModeEntries());
        commandBuilder.set("#TwSettingsAnimalAgingModeDropdown.Value", currentValues.animalAgingMode().toConfigValue());
        commandBuilder.set("#TwSettingsAnimalOldAgeDeathCheck.Value", currentValues.animalOldAgeDeathEnabled());
        commandBuilder.set("#TwSettingsHappinessEnabledCheck.Value", currentValues.happinessEnabled());
        commandBuilder.set("#TwSettingsPassiveBreedingEnabledCheck.Value", currentValues.passiveBreedingEnabled());
        commandBuilder.set("#TwSettingsBreedingRequiresHappinessCheck.Value", currentValues.breedingRequiresHappiness());
        commandBuilder.set("#TwSettingsBreedingGenderEnabledCheck.Value", currentValues.breedingGenderEnabled());
        commandBuilder.set("#TwSettingsTraitsEnabledCheck.Value", currentValues.traitsEnabled());
        commandBuilder.set("#TwSettingsLevelingEnabledCheck.Value", currentValues.levelingEnabled());
        commandBuilder.set("#TwSettingsTalentsEnabledCheck.Value", currentValues.talentsEnabled());
        commandBuilder.set("#TwSettingsReviveSystemEnabledCheck.Value", currentValues.reviveSystemEnabled());
        commandBuilder.set("#TwSettingsRecallTeleportingEnabledCheck.Value", currentValues.recallTeleportingEnabled());
        commandBuilder.set("#TwSettingsFlightUnlimitedVigourCheck.Value", currentValues.avatarFlightUnlimitedVigour());
        commandBuilder.set("#TwSettingsAnnouncementsEnabledCheck.Value", currentValues.announcementsEnabled());
        commandBuilder.set("#TwSettingsNameFilterEnabledCheck.Value", currentNameFilter.enabled());
        commandBuilder.set("#TwSettingsNameFilterUseStockListCheck.Value", currentNameFilter.useStockList());
        commandBuilder.set("#TwSettingsNameFilterBlockedWordsInput.Value",
                NameFilterSettings.formatWordList(currentNameFilter.blockedWords()));
        commandBuilder.set("#TwSettingsNameFilterAllowedWordsInput.Value",
                NameFilterSettings.formatWordList(currentNameFilter.allowedWords()));
        commandBuilder.set("#TwSettingsDeployModeDropdown.Entries", deployModeEntries());
        commandBuilder.set("#TwSettingsDeployModeDropdown.Value", currentDeploy.mode().configValue());
        commandBuilder.set("#TwSettingsDeployPlacesInput.Value", DeployPlaces.formatPlaces(currentDeploy.places()));
    }

    /** Where companions may come out, as the form holds it; a control missing from the payload keeps its value. */
    @Nonnull
    private DeployPlaces deployFrom(@Nonnull EventPayload payload) {
        return new DeployPlaces(
                payload.deployMode != null ? DeployPlaces.Mode.fromConfigValue(payload.deployMode) : currentDeploy.mode(),
                payload.deployPlaces != null ? DeployPlaces.parsePlaces(payload.deployPlaces) : currentDeploy.places());
    }

    private List<DropdownEntryInfo> deployModeEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.deployMode.everywhereExcept")),
                        DeployPlaces.Mode.EVERYWHERE_EXCEPT.configValue()),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.deployMode.onlyListed")),
                        DeployPlaces.Mode.ONLY_LISTED.configValue()));
    }

    /** The name filter the form holds; a control missing from the payload keeps its current value. */
    @Nonnull
    private NameFilterSettings nameFilterFrom(@Nonnull EventPayload payload) {
        return new NameFilterSettings(
                payload.nameFilterEnabled != null ? payload.nameFilterEnabled : currentNameFilter.enabled(),
                payload.nameFilterUseStockList != null
                        ? payload.nameFilterUseStockList
                        : currentNameFilter.useStockList(),
                payload.nameFilterBlockedWords != null
                        ? NameFilterSettings.parseWordList(payload.nameFilterBlockedWords)
                        : currentNameFilter.blockedWords(),
                payload.nameFilterAllowedWords != null
                        ? NameFilterSettings.parseWordList(payload.nameFilterAllowedWords)
                        : currentNameFilter.allowedWords());
    }

    private void updateUnsavedChanges() {
        if (draftPayload == null) {
            unsavedChanges = !currentValues.equals(savedValues) || !currentNameFilter.equals(savedNameFilter)
                    || !currentDeploy.equals(savedDeploy) || modTab.hasPendingEdits();
            return;
        }
        unsavedChanges = formParser.hasChanges(draftPayload, currentValues, savedValues)
                || !nameFilterFrom(draftPayload).equals(savedNameFilter)
                || !deployFrom(draftPayload).equals(savedDeploy)
                || modTab.hasPendingEdits();
    }

    private void renderFeedback(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.set("#TwSettingsStatusLine.Text", warningLine.isBlank() ? statusLine : warningLine);
        commandBuilder.set(
                "#TwSettingsApplyButton.Text",
                applyInProgress
                        ? resolveText("tamework.ui.settings.button.applying")
                        : resolveText("tamework.ui.shared.button.apply")
        );
        commandBuilder.set("#TwSettingsUnsavedWarning.Visible", unsavedChanges);
        commandBuilder.set("#TwSettingsStatusLine.Visible", !unsavedChanges || !warningLine.isBlank());
        commandBuilder.set("#TwSettingsUnsavedText.Text", resolveText("tamework.ui.settings.warning.unsavedChanges"));
    }

    private void refreshFeedback() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        appendFeedback(commandBuilder);
        sendUpdate(commandBuilder, null, false);
    }

    /** Rebuilds the mod setting rows, for example to show values saved by Apply, along with the feedback. */
    private void refreshModSettingRows() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        bindStaticEvents(eventBuilder);
        modTab.render(commandBuilder, eventBuilder);
        appendFeedback(commandBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void appendFeedback(@Nonnull UICommandBuilder commandBuilder) {
        renderFeedback(commandBuilder);
        if (draftPayload != null) {
            TameworkSettingsFormParser.ParseResult draft = parseValues(draftPayload);
            TameworkSettingsPreset preset = draft.success() && draft.values() != null
                    ? TameworkSettingsPreset.match(draft.values())
                    : TameworkSettingsPreset.CUSTOM;
            // A changed form must allow selecting the previous preset again.
            commandBuilder.set("#TwSettingsPresetDropdown.Value", preset.value());
            // The note under the ownership choice describes the mode selected in the form.
            renderCaptureItemOwnershipNote(commandBuilder, CaptureItemOwnershipMode.fromConfigValue(
                    draftPayload.captureItemOwnership == null
                            ? currentValues.captureItemOwnership().toConfigValue()
                            : draftPayload.captureItemOwnership));
        }
    }

    /**
     * Opens the HUD placement editor for the server default. Unapplied form edits would be lost when this page
     * closes, so the editor only opens once they are applied or refreshed away.
     */
    private void onEditHudDefault(@Nonnull EventPayload payload,
                                  @Nonnull Ref<EntityStore> ref,
                                  @Nonnull Store<EntityStore> store) {
        draftPayload = payload;
        updateUnsavedChanges();
        if (unsavedChanges || applyInProgress) {
            warningLine = resolveText("tamework.ui.settings.warning.applyBeforeHudDefault");
            statusLine = "";
            refreshFeedback();
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        openingHudDefaultEditor = true;
        try {
            player.getPageManager().openCustomPage(ref, store, new TameworkHudPlacementPage(
                    playerRef, TameworkHudPlacementPage.Mode.SERVER_DEFAULT, this::reopenSettings));
        } catch (RuntimeException failure) {
            // The settings page stays open, so it should still remind the player when it does close.
            openingHudDefaultEditor = false;
            throw failure;
        }
    }

    private void reopenSettings() {
        Ref<EntityStore> currentRef = playerRef.getReference();
        if (currentRef == null || !currentRef.isValid()) {
            return;
        }
        String error = TameworkSettingsPageService.openSettingsPage(
                currentRef, currentRef.getStore(), "hud_placement", "settings_hud_default");
        if (error != null) {
            playerRef.sendMessage(Message.raw(error));
        }
    }

    private void onApply(@Nonnull EventPayload payload, @Nonnull Store<EntityStore> store) {
        if (applyInProgress) {
            warningLine = resolveText("tamework.ui.settings.warning.applyInProgress");
            statusLine = "";
            refreshFeedback();
            return;
        }

        draftPayload = payload;
        updateUnsavedChanges();
        TameworkSettingsFormParser.ParseResult parseResult = parseValues(payload);
        if (!parseResult.success()) {
            warningLine = parseResult.message();
            statusLine = "";
            refreshFeedback();
            return;
        }
        TameworkSettingsModTab.Collected modEdits = modTab.collect();
        if (modEdits.invalidLabel() != null) {
            warningLine = formatText("tamework.ui.settings.modSettings.warning.invalidNumber", modEdits.invalidLabel());
            statusLine = "";
            refreshFeedback();
            return;
        }
        Map<String, JsonPrimitive> modChanges = modEdits.changes();
        ModSettingsService modService = plugin.getModSettingsService();

        TameworkSettingsValues requested = parseResult.values();
        NameFilterSettings requestedNameFilter = nameFilterFrom(payload);
        NameFilterSettings previousNameFilter = savedNameFilter;
        DeployPlaces requestedDeploy = deployFrom(payload);
        DeployPlaces previousDeploy = savedDeploy;
        currentValues = requested;
        currentNameFilter = requestedNameFilter;
        currentDeploy = requestedDeploy;
        applyInProgress = true;
        statusLine = resolveText("tamework.ui.settings.status.applying");
        warningLine = "";
        refreshFeedback();

        // Both saves run off the world thread; the mod settings save waits for the main one to succeed.
        CompletableFuture
                .supplyAsync(() -> applySettings(requested, requestedNameFilter, previousNameFilter,
                        requestedDeploy, previousDeploy))
                .thenCompose(outcome -> applyModSettings(outcome, modService, modChanges))
                .whenComplete((result, throwable) -> world.execute(() -> {
                    applyInProgress = false;
                    if (throwable != null || result == null) {
                        plugin.getLogger().at(Level.WARNING).withCause(throwable).log("Tamework settings apply failed.");
                        statusLine = "";
                        warningLine = resolveText("tamework.ui.settings.warning.applyFailed");
                        refreshFeedback();
                        return;
                    }
                    ApplyOutcome outcome = result.settings();
                    if (outcome == null) {
                        statusLine = "";
                        warningLine = resolveText("tamework.ui.settings.warning.applyFailed");
                    } else if (outcome.partial()) {
                        savedValues = loadSettingsValues();
                        savedNameFilter = NameFilterSettingsStore.current();
                        savedDeploy = DeployPlacesStore.cachedOrDefault();
                        updateUnsavedChanges();
                        CompanionStatModifierRefreshService.refreshLoadedNpcStatModifiers(store);
                        statusLine = outcome.message();
                        warningLine = outcome.warning();
                    } else if (outcome.success()) {
                        savedValues = loadSettingsValues();
                        savedNameFilter = NameFilterSettingsStore.current();
                        savedDeploy = DeployPlacesStore.cachedOrDefault();
                        updateUnsavedChanges();
                        CompanionStatModifierRefreshService.refreshLoadedNpcStatModifiers(store);
                        statusLine = outcome.message();
                        warningLine = "";
                    } else {
                        statusLine = "";
                        warningLine = outcome.warning();
                    }
                    if (modChanges.isEmpty() || outcome == null || !outcome.success()) {
                        refreshFeedback();
                        return;
                    }
                    showModApplyResult(result, modEdits);
                    refreshModSettingRows();
                }));
    }

    /** Saves mod setting edits after the main settings saved; completes with both results. */
    @Nonnull
    private static CompletableFuture<ApplyResult> applyModSettings(@Nullable ApplyOutcome outcome,
                                                                   @Nullable ModSettingsService service,
                                                                   @Nonnull Map<String, JsonPrimitive> changes) {
        if (outcome == null || !outcome.success() || changes.isEmpty()) {
            return CompletableFuture.completedFuture(new ApplyResult(outcome, null, null));
        }
        if (service == null) {
            return CompletableFuture.completedFuture(new ApplyResult(outcome, null,
                    new IllegalStateException("Mod settings service is not available.")));
        }
        try {
            return service.apply(changes)
                    .handle((mods, failure) -> new ApplyResult(outcome, mods, failure))
                    .toCompletableFuture();
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new ApplyResult(outcome, null, failure));
        }
    }

    /** Adds the mod settings outcome to the status line; a failed save keeps the edits pending. */
    private void showModApplyResult(@Nonnull ApplyResult result, @Nonnull TameworkSettingsModTab.Collected modEdits) {
        if (result.modFailure() != null || result.mods() == null) {
            plugin.getLogger().at(Level.WARNING).withCause(result.modFailure()).log("Saving mod settings from /tw settings failed.");
            warningLine = joinSentences(warningLine, resolveText("tamework.ui.settings.modSettings.warning.saveFailed"));
            return;
        }
        modTab.markApplied(modEdits);
        updateUnsavedChanges();
        switch (result.mods()) {
            case APPLIED -> statusLine = joinSentences(statusLine,
                    resolveText("tamework.ui.settings.modSettings.status.applied"));
            case RESTART_REQUIRED -> statusLine = joinSentences(statusLine,
                    resolveText("tamework.ui.settings.modSettings.status.restart"));
            case PATCH_FAILURES -> warningLine = joinSentences(warningLine,
                    resolveText("tamework.ui.settings.modSettings.warning.patchFailures"));
            case NOT_REGENERATED -> warningLine = joinSentences(warningLine,
                    resolveText("tamework.ui.settings.modSettings.warning.notRegenerated"));
        }
    }

    @Nonnull
    private static String joinSentences(@Nonnull String first, @Nonnull String second) {
        return first.isBlank() ? second : first + " " + second;
    }

    private void onLoadPreset(@Nonnull EventPayload payload) {
        TameworkSettingsPreset preset = TameworkSettingsPreset.fromConfigValue(payload.preset);
        if (!preset.isLoadable()) {
            statusLine = resolveText("tamework.ui.settings.status.selectPreset");
            warningLine = "";
            refreshFeedback();
            return;
        }
        TameworkSettingsFormParser.ParseResult parseResult = parseValues(payload);
        TameworkSettingsValues baseValues = parseResult.success() && parseResult.values() != null
                ? parseResult.values()
                : currentValues;
        currentValues = preset.applyTo(baseValues);
        // Presets do not cover the name filter or where companions may come out; keep what the form holds.
        currentNameFilter = nameFilterFrom(payload);
        currentDeploy = deployFrom(payload);
        draftPayload = null;
        updateUnsavedChanges();
        statusLine = parseResult.success()
                ? formatText("tamework.ui.settings.status.loadedPreset", preset.displayName(resolveLanguage()))
                : formatText("tamework.ui.settings.status.loadedPresetDiscardedInvalid", preset.displayName(resolveLanguage()));
        warningLine = "";
        refreshUi();
    }

    @Nonnull
    private ApplyOutcome applySettings(@Nonnull TameworkSettingsValues values,
                                       @Nonnull NameFilterSettings nameFilter,
                                       @Nonnull NameFilterSettings savedFilter,
                                       @Nonnull DeployPlaces deploy,
                                       @Nonnull DeployPlaces savedDeployPlaces) {
        Path globalSettingsPath = resolveSettingsDirectory().resolve(TameworkSettingsStore.GLOBAL_SETTINGS_FILE_NAME);
        TameworkSettingsStore.GlobalSettingsSnapshot snapshot = values.toGlobalSettingsSnapshot();
        if (!TameworkSettingsStore.saveGlobalSettings(globalSettingsPath, snapshot, plugin.getLogger())) {
            return ApplyOutcome.failure(resolveText("tamework.ui.settings.warning.saveFailed"));
        }
        plugin.onRuntimeSettingsChanged();
        List<String> warnings = new ArrayList<>();
        // An unchanged filter is not rewritten, so a hand edit to its file survives other applies.
        if (!nameFilter.equals(savedFilter) && !NameFilterSettingsStore.save(
                NameFilterSettingsStore.resolveFile(plugin), nameFilter, plugin.getLogger())) {
            warnings.add(resolveText("tamework.ui.settings.warning.nameFilterSaveFailed"));
        }
        if (!deploy.equals(savedDeployPlaces) && !DeployPlacesStore.save(
                DeployPlacesStore.resolveFile(plugin), deploy, plugin.getLogger())) {
            warnings.add(resolveText("tamework.ui.settings.warning.deploySaveFailed"));
        }
        if (!TameworkSettingsAnnouncementStore.saveEnabled(
                TameworkSettingsAnnouncementStore.resolveAnnouncementConfigFile(plugin),
                values.announcementsEnabled(), plugin.getLogger())) {
            warnings.add(resolveText("tamework.ui.settings.warning.announcementSaveFailed"));
        }
        if (!warnings.isEmpty()) {
            return ApplyOutcome.partial(resolveText("tamework.ui.settings.status.applied"),
                    String.join(" ", warnings));
        }

        return ApplyOutcome.success(resolveText("tamework.ui.settings.status.applied"));
    }

    @Nonnull
    private Path resolveSettingsDirectory() {
        return TameworkSettingsStore.resolveSettingsDirectory(plugin);
    }

    @Nonnull
    private TameworkSettingsFormParser.ParseResult parseValues(@Nonnull EventPayload payload) {
        return formParser.parse(payload, currentValues);
    }

    private void refreshUi() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        render(commandBuilder);
        bindStaticEvents(eventBuilder);
        modTab.render(commandBuilder, eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private List<DropdownEntryInfo> populationScopeEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.populationScope.perWorld")),
                        TwGlobalConfig.PerPlayerLimitScope.PER_WORLD.configValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.populationScope.global")),
                        TwGlobalConfig.PerPlayerLimitScope.GLOBAL.configValue()
                )
        );
    }

    private TameworkSettingsValues loadSettingsValues() {
        boolean enabled = TameworkSettingsAnnouncementStore.loadResolvedAnnouncement(
                TameworkSettingsAnnouncementStore.resolveAnnouncementConfigFile(plugin), plugin.getLogger()).enabled();
        return TameworkSettingsValues.fromRuntime(enabled);
    }

    private void loadCurrentValues() {
        currentValues = loadSettingsValues();
        savedValues = currentValues;
        currentNameFilter = NameFilterSettingsStore.current();
        savedNameFilter = currentNameFilter;
        currentDeploy = DeployPlacesStore.cachedOrDefault();
        savedDeploy = currentDeploy;
        modTab.discardPendingEdits();
        draftPayload = null;
        unsavedChanges = false;
        warningLine = "";
    }

    private List<DropdownEntryInfo> needsTickPolicyModeEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsTickPolicy.ownerGraceThenDecay")),
                        TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsTickPolicy.anyLoadedPlayer")),
                        TwNeedsConfig.TickPolicyMode.ANY_LOADED_PLAYER.toConfigValue()
                )
        );
    }

    private List<DropdownEntryInfo> captureItemOwnershipEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (CaptureItemOwnershipMode mode : CaptureItemOwnershipMode.values()) {
            entries.add(new DropdownEntryInfo(
                    LocalizableString.fromString(resolveText(captureItemOwnershipKey(mode))),
                    mode.toConfigValue()
            ));
        }
        return entries;
    }

    /** The one-line description of {@code mode}, shown under the ownership choice. */
    private void renderCaptureItemOwnershipNote(@Nonnull UICommandBuilder commandBuilder,
                                                @Nonnull CaptureItemOwnershipMode mode) {
        commandBuilder.set("#TwSettingsCaptureItemOwnershipNoteLabel.Text",
                resolveText(captureItemOwnershipKey(mode) + ".description"));
    }

    @Nonnull
    private static String captureItemOwnershipKey(@Nonnull CaptureItemOwnershipMode mode) {
        return "tamework.ui.settings.captureItemOwnership." + switch (mode) {
            case FOLLOWS_ITEM -> "followsItem";
            case OWNER_ONLY -> "ownerOnly";
            case CHANGES_ON_RELEASE -> "changesOnRelease";
        };
    }

    private List<DropdownEntryInfo> animalAgingModeEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.animalAgingMode.off")),
                        AnimalAgingMode.OFF.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.animalAgingMode.freezeAtPrime")),
                        AnimalAgingMode.FREEZE_AT_PRIME.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.animalAgingMode.full")),
                        AnimalAgingMode.FULL.toConfigValue()
                )
        );
    }

    private List<DropdownEntryInfo> needsDamageModelEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsDamageModel.minOnlyPercent")),
                        TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsDamageModel.minOnlyFlat")),
                        TwNeedsConfig.DamageModel.MIN_ONLY_FLAT.toConfigValue()
                )
        );
    }

    private List<DropdownEntryInfo> needsDamageDualNeedRuleEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.dualNeedRule.useHigherOnly")),
                        TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.dualNeedRule.sumBoth")),
                        TwNeedsConfig.DualNeedRule.SUM_BOTH.toConfigValue()
                )
        );
    }

    private List<DropdownEntryInfo> needsResourceModeEntries() {
        return List.of(
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsResourceMode.accurate")),
                        NeedsResourceMode.ACCURATE.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsResourceMode.autoFast")),
                        NeedsResourceMode.AUTO_FAST.toConfigValue()
                ),
                new DropdownEntryInfo(
                        LocalizableString.fromString(resolveText("tamework.ui.settings.needsResourceMode.alwaysFast")),
                        NeedsResourceMode.ALWAYS_FAST.toConfigValue()
                )
        );
    }

    @Nullable
    private String resolveLanguage() {
        return playerRef != null ? playerRef.getLanguage() : null;
    }

    @Nonnull
    private String resolveText(@Nonnull String key) {
        return LocalizedText.resolve(playerRef, key);
    }

    @Nonnull
    private String formatText(@Nonnull String key, Object... args) {
        return LocalizedText.format(playerRef, key, args);
    }

    @Nonnull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
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

    /** The main settings outcome, plus the mod settings outcome or failure when mod edits were saved. */
    private record ApplyResult(@Nullable ApplyOutcome settings,
                               @Nullable ModSettingsService.ApplyOutcome mods,
                               @Nullable Throwable modFailure) {
    }

    /** Event payload for settings page actions. */
    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action).add()
                .<String>append(new KeyedCodec<>(KEY_PRESET, Codec.STRING), (x, v) -> x.preset = v, x -> x.preset).add()
                .<String>append(new KeyedCodec<>(KEY_POP_LIMIT, Codec.STRING), (x, v) -> x.populationLimit = v, x -> x.populationLimit).add()
                .<String>append(new KeyedCodec<>(KEY_POP_DEPLOYED_LIMIT, Codec.STRING), (x, v) -> x.populationDeployedLimit = v, x -> x.populationDeployedLimit).add()
                .<String>append(new KeyedCodec<>(KEY_POP_SCOPE, Codec.STRING), (x, v) -> x.populationScope = v, x -> x.populationScope).add()
                .<String>append(new KeyedCodec<>(KEY_COMMAND_PANEL_CARDS_PER_PAGE, Codec.STRING), (x, v) -> x.commandPanelCardsPerPage = v, x -> x.commandPanelCardsPerPage).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SIMPLE_CLAIMS_ENABLED, Codec.BOOLEAN), (x, v) -> x.simpleClaimsEnabled = v, x -> x.simpleClaimsEnabled).add()
                .<String>append(new KeyedCodec<>(KEY_CLAIM_LIMIT_CHUNK, Codec.STRING), (x, v) -> x.claimLimitChunk = v, x -> x.claimLimitChunk).add()
                .<String>append(new KeyedCodec<>(KEY_CLAIM_LIMIT_TOTAL, Codec.STRING), (x, v) -> x.claimLimitTotal = v, x -> x.claimLimitTotal).add()
                .<Boolean>append(new KeyedCodec<>(KEY_BREEDING_REQUIRES_CLAIM, Codec.BOOLEAN), (x, v) -> x.breedingRequiresClaim = v, x -> x.breedingRequiresClaim).add()
                .<Boolean>append(new KeyedCodec<>(KEY_SIMPLE_CLAIMS_PROTECT, Codec.BOOLEAN), (x, v) -> x.simpleClaimsProtect = v, x -> x.simpleClaimsProtect).add()
                .<Boolean>append(new KeyedCodec<>(KEY_OWNERSHIP_BLOCK_OWNER_DAMAGE, Codec.BOOLEAN), (x, v) -> x.blockOwnerDamage = v, x -> x.blockOwnerDamage).add()
                .<Boolean>append(new KeyedCodec<>(KEY_OWNERSHIP_BLOCK_ALL_DAMAGE_IF_OWNED, Codec.BOOLEAN), (x, v) -> x.blockAllDamageIfOwned = v, x -> x.blockAllDamageIfOwned).add()
                .<Boolean>append(new KeyedCodec<>(KEY_OWNERSHIP_INVULNERABLE_IF_OWNED, Codec.BOOLEAN), (x, v) -> x.invulnerableIfOwned = v, x -> x.invulnerableIfOwned).add()
                .<String>append(new KeyedCodec<>(KEY_CAPTURE_ITEM_OWNERSHIP, Codec.STRING), (x, v) -> x.captureItemOwnership = v, x -> x.captureItemOwnership).add()
                .<Boolean>append(new KeyedCodec<>(KEY_CAPTURE_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.captureRequiresOwner = v, x -> x.captureRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_INTERACTION_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.interactionRequiresOwner = v, x -> x.interactionRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_LINKING_REQUIRES_OWNER, Codec.BOOLEAN), (x, v) -> x.linkingRequiresOwner = v, x -> x.linkingRequiresOwner).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NEEDS_ENABLED, Codec.BOOLEAN), (x, v) -> x.needsEnabled = v, x -> x.needsEnabled).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_RESOURCE_MODE, Codec.STRING), (x, v) -> x.needsResourceMode = v, x -> x.needsResourceMode).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_ENABLED, Codec.BOOLEAN), (x, v) -> x.needsDamageEnabled = v, x -> x.needsDamageEnabled).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_TICK_POLICY_MODE, Codec.STRING), (x, v) -> x.needsTickPolicyMode = v, x -> x.needsTickPolicyMode).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_OWNER_OFFLINE_GRACE_HOURS, Codec.STRING), (x, v) -> x.needsOwnerOfflineGraceHours = v, x -> x.needsOwnerOfflineGraceHours).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_OWNER_OFFLINE_DECAY_MULTIPLIER, Codec.STRING), (x, v) -> x.needsOwnerOfflineDecayMultiplier = v, x -> x.needsOwnerOfflineDecayMultiplier).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_MODEL, Codec.STRING), (x, v) -> x.needsDamageModel = v, x -> x.needsDamageModel).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_DUAL_NEED_RULE, Codec.STRING), (x, v) -> x.needsDamageDualNeedRule = v, x -> x.needsDamageDualNeedRule).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_STARVATION_DAMAGE_PER_MINUTE, Codec.STRING), (x, v) -> x.needsStarvationDamagePerMinute = v, x -> x.needsStarvationDamagePerMinute).add()
                .<String>append(new KeyedCodec<>(KEY_NEEDS_DEHYDRATION_DAMAGE_PER_MINUTE, Codec.STRING), (x, v) -> x.needsDehydrationDamagePerMinute = v, x -> x.needsDehydrationDamagePerMinute).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NEEDS_DAMAGE_LETHAL, Codec.BOOLEAN), (x, v) -> x.needsDamageLethal = v, x -> x.needsDamageLethal).add()
                .<String>append(new KeyedCodec<>(KEY_ANIMAL_AGING_MODE, Codec.STRING), (x, v) -> x.animalAgingMode = v, x -> x.animalAgingMode).add()
                .<Boolean>append(new KeyedCodec<>(KEY_ANIMAL_OLD_AGE_DEATH, Codec.BOOLEAN), (x, v) -> x.animalOldAgeDeathEnabled = v, x -> x.animalOldAgeDeathEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_HAPPINESS_ENABLED, Codec.BOOLEAN), (x, v) -> x.happinessEnabled = v, x -> x.happinessEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_PASSIVE_BREEDING_ENABLED, Codec.BOOLEAN), (x, v) -> x.passiveBreedingEnabled = v, x -> x.passiveBreedingEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_BREEDING_REQUIRES_HAPPINESS, Codec.BOOLEAN), (x, v) -> x.breedingRequiresHappiness = v, x -> x.breedingRequiresHappiness).add()
                .<Boolean>append(new KeyedCodec<>(KEY_BREEDING_GENDER_ENABLED, Codec.BOOLEAN), (x, v) -> x.breedingGenderEnabled = v, x -> x.breedingGenderEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_TRAITS_ENABLED, Codec.BOOLEAN), (x, v) -> x.traitsEnabled = v, x -> x.traitsEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_LEVELING_ENABLED, Codec.BOOLEAN), (x, v) -> x.levelingEnabled = v, x -> x.levelingEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_TALENTS_ENABLED, Codec.BOOLEAN), (x, v) -> x.talentsEnabled = v, x -> x.talentsEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_REVIVE_SYSTEM_ENABLED, Codec.BOOLEAN), (x, v) -> x.reviveSystemEnabled = v, x -> x.reviveSystemEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_RECALL_TELEPORTING_ENABLED, Codec.BOOLEAN), (x, v) -> x.recallTeleportingEnabled = v, x -> x.recallTeleportingEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_FLIGHT_UNLIMITED_VIGOUR, Codec.BOOLEAN), (x, v) -> x.avatarFlightUnlimitedVigour = v, x -> x.avatarFlightUnlimitedVigour).add()
                .<Boolean>append(new KeyedCodec<>(KEY_ANNOUNCEMENTS_ENABLED, Codec.BOOLEAN), (x, v) -> x.announcementsEnabled = v, x -> x.announcementsEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NAME_FILTER_ENABLED, Codec.BOOLEAN), (x, v) -> x.nameFilterEnabled = v, x -> x.nameFilterEnabled).add()
                .<Boolean>append(new KeyedCodec<>(KEY_NAME_FILTER_USE_STOCK_LIST, Codec.BOOLEAN), (x, v) -> x.nameFilterUseStockList = v, x -> x.nameFilterUseStockList).add()
                .<String>append(new KeyedCodec<>(KEY_NAME_FILTER_BLOCKED_WORDS, Codec.STRING), (x, v) -> x.nameFilterBlockedWords = v, x -> x.nameFilterBlockedWords).add()
                .<String>append(new KeyedCodec<>(KEY_NAME_FILTER_ALLOWED_WORDS, Codec.STRING), (x, v) -> x.nameFilterAllowedWords = v, x -> x.nameFilterAllowedWords).add()
                .<String>append(new KeyedCodec<>(KEY_DEPLOY_MODE, Codec.STRING), (x, v) -> x.deployMode = v, x -> x.deployMode).add()
                .<String>append(new KeyedCodec<>(KEY_DEPLOY_PLACES, Codec.STRING), (x, v) -> x.deployPlaces = v, x -> x.deployPlaces).add()
                .<String>append(new KeyedCodec<>(KEY_TAB, Codec.STRING), (x, v) -> x.tab = v, x -> x.tab).add()
                .<String>append(new KeyedCodec<>(TameworkSettingsModTab.KEY_ID, Codec.STRING), (x, v) -> x.modSettingId = v, x -> x.modSettingId).add()
                .<Boolean>append(new KeyedCodec<>(TameworkSettingsModTab.KEY_BOOL, Codec.BOOLEAN), (x, v) -> x.modSettingBool = v, x -> x.modSettingBool).add()
                .<String>append(new KeyedCodec<>(TameworkSettingsModTab.KEY_VALUE, Codec.STRING), (x, v) -> x.modSettingValue = v, x -> x.modSettingValue).add()
                .build();

        String action;
        String preset;
        String populationLimit;
        String populationDeployedLimit;
        String populationScope;
        String commandPanelCardsPerPage;
        Boolean simpleClaimsEnabled;
        String claimLimitChunk;
        String claimLimitTotal;
        Boolean breedingRequiresClaim;
        Boolean simpleClaimsProtect;
        Boolean blockOwnerDamage;
        Boolean blockAllDamageIfOwned;
        Boolean invulnerableIfOwned;
        String captureItemOwnership;
        Boolean captureRequiresOwner;
        Boolean interactionRequiresOwner;
        Boolean linkingRequiresOwner;
        Boolean needsEnabled;
        String needsResourceMode;
        Boolean needsDamageEnabled;
        String needsTickPolicyMode;
        String needsOwnerOfflineGraceHours;
        String needsOwnerOfflineDecayMultiplier;
        String needsDamageModel;
        String needsDamageDualNeedRule;
        String needsStarvationDamagePerMinute;
        String needsDehydrationDamagePerMinute;
        Boolean needsDamageLethal;
        String animalAgingMode;
        Boolean animalOldAgeDeathEnabled;
        Boolean happinessEnabled;
        Boolean passiveBreedingEnabled;
        Boolean breedingRequiresHappiness;
        Boolean breedingGenderEnabled;
        Boolean traitsEnabled;
        Boolean levelingEnabled;
        Boolean talentsEnabled;
        Boolean reviveSystemEnabled;
        Boolean recallTeleportingEnabled;
        Boolean avatarFlightUnlimitedVigour;
        Boolean announcementsEnabled;
        Boolean nameFilterEnabled;
        Boolean nameFilterUseStockList;
        String nameFilterBlockedWords;
        String nameFilterAllowedWords;
        String deployMode;
        String deployPlaces;
        String tab;
        String modSettingId;
        Boolean modSettingBool;
        String modSettingValue;
    }
}
