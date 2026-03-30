package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.alechilles.alecstamework.config.overrides.TwConfigJsonUtil;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.config.overrides.TwConfigSnapshot;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Global-only in-world config editor page (asset-editor style property grid).
 */
public final class TameworkConfigEditorPage
        extends InteractiveCustomUIPage<TameworkConfigEditorPage.EventPayload> {
    public static final String UI_PATH = "TameworkConfigEditorPage.ui";
    private static final String UI_SECTION_ROW = "TameworkConfigEditorSectionRow.ui";
    private static final String UI_FIELD_ROW = "TameworkConfigEditorFieldRow.ui";

    private static final String K_ACTION = "Action";
    private static final String K_PATH = "Path";
    private static final String K_FIELD_VALUE = "@FieldValueInput";
    private static final String K_ASSET_KEY = "@SelectedAssetKey";
    private static final String K_ASSET_FILTER = "@AssetFilterInput";
    private static final String K_PROPERTY_FILTER = "@PropertyFilterInput";

    private static final String A_REFRESH = "Refresh";
    private static final String A_APPLY_REQUEST = "ApplyRequest";
    private static final String A_APPLY_CONFIRM = "ApplyConfirm";
    private static final String A_APPLY_CANCEL = "ApplyCancel";
    private static final String A_CLOSE = "Close";
    private static final String A_TOGGLE_SECTION = "ToggleSection";
    private static final String A_RESET = "Reset";
    private static final String A_SET_VALUE = "SetValue";
    private static final String PARENT_NONE_VALUE = "__none__";

    private static final SectionDef S_GENERAL = new SectionDef("general", "tamework.ui.configEditor.section.general", 0, null);
    private static final SectionDef S_OWNERSHIP = new SectionDef("ownership", "tamework.ui.configEditor.section.ownership", 0, null);
    private static final SectionDef S_INTERACTION = new SectionDef("interaction", "tamework.ui.configEditor.section.interaction", 0, null);
    private static final SectionDef S_COMMAND = new SectionDef("command", "tamework.ui.configEditor.section.command", 0, null);
    private static final SectionDef S_ASSET_SETS = new SectionDef("assetsets", "tamework.ui.configEditor.section.assetSets", 0, null);
    private static final SectionDef S_POPULATION = new SectionDef("population", "tamework.ui.configEditor.section.population", 0, null);
    private static final SectionDef S_SIMPLE = new SectionDef("simpleclaims", "tamework.ui.configEditor.section.simpleClaims", 0, null);
    private static final SectionDef S_SIMPLE_BREED = new SectionDef("simpleclaims-breeding", "tamework.ui.configEditor.section.breeding", 1, S_SIMPLE.id);
    private static final SectionDef S_SIMPLE_DAMAGE = new SectionDef("simpleclaims-damage", "tamework.ui.configEditor.section.damage", 1, S_SIMPLE.id);

    private static final List<RowDef> LAYOUT = buildLayout();
    private static final Map<String, FieldDef> FIELDS = buildFields();
    private static final Map<String, SectionDef> SECTIONS = buildSections();
    private static final Set<String> KNOWN_TOP_LEVEL_KEYS = buildKnownTopLevelKeys();
    private static final Map<String, JsonElement> INHERITED_FALLBACK_VALUES = buildInheritedFallbackValues();

    private final Tamework plugin;
    private final World world;
    private final TwConfigOverrideManager overrideManager;

    private TwConfigSnapshot snapshot;
    private final LinkedHashMap<String, TwConfigAssetDescriptor> descriptorByKey = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> sourceByKey = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> diskByKey = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> draftByKey = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashMap<String, String>> inputByDescriptor = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashMap<String, String>> validationByDescriptor = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> collapsedSections = new LinkedHashMap<>();

    private String selectedDescriptorKey;
    private String assetFilter = "";
    private String propertyFilter = "";
    private String statusLine = "";
    private String warningLine = "";
    private boolean applyConfirmVisible = false;
    private boolean applyInProgress = false;

    public TameworkConfigEditorPage(@Nonnull PlayerRef playerRef,
                                    @Nonnull Tamework plugin,
                                    @Nonnull World world) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.overrideManager = Objects.requireNonNull(plugin.getConfigOverrideManager(), "overrideManager");
        initializeCollapsedState();
        reloadSnapshot(false);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append(UI_PATH);
        bindStaticEvents(eventBuilder);
        render(commandBuilder, eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull EventPayload data) {
        String action = trim(data.action);
        if (!action.isBlank()) {
            switch (action) {
                case A_CLOSE -> close();
                case A_REFRESH -> {
                    reloadSnapshot(true);
                    applyConfirmVisible = false;
                    statusLine = tr("tamework.ui.configEditor.status.refreshed");
                    warningLine = "";
                    refreshUi();
                }
                case A_APPLY_REQUEST -> {
                    onApplyRequest();
                    refreshUi();
                }
                case A_APPLY_CONFIRM -> {
                    onApplyConfirm();
                    refreshUi();
                }
                case A_APPLY_CANCEL -> {
                    applyConfirmVisible = false;
                    refreshUi();
                }
                case A_TOGGLE_SECTION -> {
                    toggleSection(data.path);
                    refreshUi();
                }
                case A_RESET -> {
                    resetField(data.path);
                    refreshUi();
                }
                case A_SET_VALUE -> {
                    FieldDef field = FIELDS.get(trim(data.path));
                    stageValue(data.path, data.value);
                    if (shouldRefreshAfterSetValue(field)) {
                        refreshUi();
                    }
                }
                default -> {
                }
            }
            return;
        }

        boolean shouldRefresh = false;
        if (data.assetFilter != null && !looksLikeSelectorExpression(data.assetFilter)) {
            String nextAssetFilter = sanitizeFilter(data.assetFilter);
            if (!Objects.equals(nextAssetFilter, assetFilter)) {
                assetFilter = nextAssetFilter;
                ensureSelectedDescriptor();
                shouldRefresh = true;
            }
        }
        if (data.propertyFilter != null && !looksLikeSelectorExpression(data.propertyFilter)) {
            String nextPropertyFilter = sanitizeFilter(data.propertyFilter);
            if (!Objects.equals(nextPropertyFilter, propertyFilter)) {
                propertyFilter = nextPropertyFilter;
                shouldRefresh = true;
            }
        }
        if (data.assetKey != null) {
            String key = resolveDescriptorKey(trim(data.assetKey));
            if (key != null && !Objects.equals(key, selectedDescriptorKey)) {
                selectedDescriptorKey = key;
                warningLine = "";
                statusLine = "";
                shouldRefresh = true;
            }
        }
        if (shouldRefresh) {
            refreshUi();
        }
    }

    private void onApplyRequest() {
        if (applyInProgress) {
            warningLine = tr("tamework.ui.configEditor.warning.applyInProgress");
            statusLine = "";
            return;
        }
        applyConfirmVisible = false;
        String validationError = firstValidationError();
        if (validationError != null) {
            warningLine = validationError;
            statusLine = "";
            return;
        }
        warningLine = "";
        statusLine = "";
        applyConfirmVisible = true;
    }

    private void onApplyConfirm() {
        if (applyInProgress) {
            warningLine = tr("tamework.ui.configEditor.warning.applyInProgress");
            statusLine = "";
            return;
        }
        applyConfirmVisible = false;
        String validationError = firstValidationError();
        if (validationError != null) {
            warningLine = validationError;
            statusLine = "";
            return;
        }

        TwConfigSnapshot snapshotAtSubmit = snapshot;
        TwConfigAssetDescriptor selectedAtSubmit = selectedDescriptor();
        LinkedHashMap<TwConfigAssetDescriptor, JsonObject> drafted = new LinkedHashMap<>();
        for (TwConfigAssetDescriptor descriptor : descriptorByKey.values()) {
            drafted.put(descriptor, TwConfigJsonUtil.copyObject(draftByKey.get(descriptor.descriptorKey())));
        }

        applyInProgress = true;
        statusLine = tr("tamework.ui.configEditor.status.applying");
        warningLine = "";
        refreshUi();

        CompletableFuture
                .supplyAsync(() -> overrideManager.applyDraft(world, snapshotAtSubmit, drafted, selectedAtSubmit))
                .whenComplete((result, throwable) -> world.execute(() -> {
                    applyInProgress = false;
                    if (throwable != null || result == null) {
                        plugin.getLogger().at(Level.WARNING).withCause(throwable).log("TwConfig async apply failed.");
                        statusLine = "";
                        warningLine = tr("tamework.ui.configEditor.warning.applyFailed");
                        refreshUi();
                        return;
                    }
                    if (result.isSuccess()) {
                        reloadSnapshot(false);
                        statusLine = result.getMessage();
                        warningLine = "";
                        refreshUi();
                        return;
                    }
                    statusLine = "";
                    warningLine = result.getMessage();
                    refreshUi();
                }));
    }

    private void reloadSnapshot(boolean clearStatus) {
        snapshot = overrideManager.createSnapshot(world);
        descriptorByKey.clear();
        sourceByKey.clear();
        diskByKey.clear();
        draftByKey.clear();

        for (TwConfigAssetDescriptor descriptor : snapshot.getDescriptors()) {
            if (descriptor.family() != TwConfigFamily.GLOBAL) {
                continue;
            }
            String key = descriptor.descriptorKey();
            descriptorByKey.put(key, descriptor);
            JsonObject source = overrideManager.readSourceJson(descriptor);
            JsonObject disk = overrideManager.readOverrideJson(world, descriptor);
            sourceByKey.put(key, source);
            diskByKey.put(key, disk);
            draftByKey.put(key, TwConfigJsonUtil.copyObject(disk));
        }

        inputByDescriptor.keySet().retainAll(descriptorByKey.keySet());
        validationByDescriptor.keySet().retainAll(descriptorByKey.keySet());
        ensureSelectedDescriptor();
        if (clearStatus) {
            statusLine = "";
            warningLine = "";
        }
    }

    private void ensureSelectedDescriptor() {
        if (descriptorByKey.isEmpty()) {
            selectedDescriptorKey = null;
            return;
        }
        if (selectedDescriptorKey != null && descriptorByKey.containsKey(selectedDescriptorKey)) {
            return;
        }
        List<TwConfigAssetDescriptor> filtered = filteredDescriptors();
        selectedDescriptorKey = filtered.isEmpty()
                ? descriptorByKey.values().iterator().next().descriptorKey()
                : filtered.get(0).descriptorKey();
    }

    private void initializeCollapsedState() {
        collapsedSections.clear();
        collapsedSections.put(S_GENERAL.id, true);
        collapsedSections.put(S_OWNERSHIP.id, true);
        collapsedSections.put(S_INTERACTION.id, true);
        collapsedSections.put(S_COMMAND.id, true);
        collapsedSections.put(S_ASSET_SETS.id, true);
        collapsedSections.put(S_POPULATION.id, true);
        collapsedSections.put(S_SIMPLE.id, true);
        collapsedSections.put(S_SIMPLE_BREED.id, true);
        collapsedSections.put(S_SIMPLE_DAMAGE.id, true);
    }

    private void bindStaticEvents(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TwConfigRefreshButton", EventData.of(K_ACTION, A_REFRESH), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TwConfigApplyButton", EventData.of(K_ACTION, A_APPLY_REQUEST), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TwConfigCloseButton", EventData.of(K_ACTION, A_CLOSE), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TwConfigAssetFilterInput", EventData.of(K_ASSET_FILTER, "#TwConfigAssetFilterInput.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TwConfigPropertyFilterInput", EventData.of(K_PROPERTY_FILTER, "#TwConfigPropertyFilterInput.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#TwConfigAssetDropdown", EventData.of(K_ASSET_KEY, "#TwConfigAssetDropdown.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TwConfigConfirmApplyButton", EventData.of(K_ACTION, A_APPLY_CONFIRM), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TwConfigCancelApplyButton", EventData.of(K_ACTION, A_APPLY_CANCEL), false);
    }

    private void render(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        ensureSelectedDescriptor();
        TwConfigAssetDescriptor selected = selectedDescriptor();

        commandBuilder.set("#TwConfigAssetFilterInput.Value", assetFilter);
        commandBuilder.set("#TwConfigPropertyFilterInput.Value", propertyFilter);
        commandBuilder.set("#TwConfigAssetDropdown.Entries", assetDropdownEntries());
        commandBuilder.set("#TwConfigAssetDropdown.Value", selectedDescriptorKey == null ? "" : selectedDescriptorKey);
        commandBuilder.set("#TwConfigAssetCount.Text", tr("tamework.ui.configEditor.assetCount", filteredDescriptors().size()));
        String resolvedWarning = resolvedWarningLine();
        String inlineNotice = resolvedWarning.isBlank() ? statusLine : resolvedWarning;
        commandBuilder.set("#TwConfigStatusLine.Text", inlineNotice);
        commandBuilder.set("#TwConfigInlineNotice.Text", inlineNotice);
        commandBuilder.set("#TwConfigWarningLine.Text", "");
        commandBuilder.set("#TwConfigApplyConfirmOverlay.Visible", applyConfirmVisible);
        commandBuilder.set("#TwConfigApplyConfirmCount.Text", tr("tamework.ui.configEditor.pendingDraftCount", pendingDraftFileCount()));

        if (selected == null) {
            commandBuilder.set("#TwConfigSelectedAssetTitle.Text", tr("tamework.ui.configEditor.noAssets"));
            commandBuilder.set("#TwConfigSelectedAssetMeta.Text", "");
            commandBuilder.set("#TwConfigSelectedAssetChain.Text", "");
            commandBuilder.set("#TwConfigOverridePath.Text", "");
            commandBuilder.set("#TwConfigPropertyEmptyState.Visible", true);
            commandBuilder.clear("#TwConfigPropertyRows");
            return;
        }

        commandBuilder.set("#TwConfigSelectedAssetTitle.Text", selected.assetId());
        commandBuilder.set("#TwConfigSelectedAssetMeta.Text", tr("tamework.ui.configEditor.selectedAssetMeta", selected.sourcePackKey()));
        commandBuilder.set("#TwConfigSelectedAssetChain.Text", inheritanceChainText(selected));
        commandBuilder.set("#TwConfigOverridePath.Text", shortenedPathForUi(overrideManager.resolveOverridePath(world, selected)));
        commandBuilder.set("#TwConfigPropertyEmptyState.Visible", false);
        renderRows(selected, commandBuilder, eventBuilder);
    }

    private void renderRows(@Nonnull TwConfigAssetDescriptor descriptor,
                            @Nonnull UICommandBuilder commandBuilder,
                            @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#TwConfigPropertyRows");
        String descriptorKey = descriptor.descriptorKey();
        JsonObject effective = effectiveJson(descriptorKey, new HashSet<>());
        JsonObject merged = mergedCurrentJson(descriptorKey);
        JsonObject draft = draft(descriptorKey);
        JsonObject disk = disk(descriptorKey);
        List<RowDef> visibleRows = visibleRows(descriptor);

        for (int i = 0; i < visibleRows.size(); i++) {
            RowDef row = visibleRows.get(i);
            String root = "#TwConfigPropertyRows[" + i + "]";
            if (row.section != null) {
                SectionDef section = row.section;
                commandBuilder.append("#TwConfigPropertyRows", UI_SECTION_ROW);
                boolean collapsed = collapsedSections.getOrDefault(section.id, false);
                int depthLevel = depthBucket(section.depth);
                String toggleText = tr(section.label);
                String countText = formatFieldCount(sectionFieldCount(section.id));
                boolean hasStagedEdits = sectionHasStagedOverrides(section.id, draft, disk);
                boolean hasAppliedLocal = !hasStagedEdits && sectionHasAppliedOverrides(section.id, disk);
                boolean showBadge = hasStagedEdits || hasAppliedLocal;

                commandBuilder.set(root + " #SectionRowBackground.Background", sectionBackgroundColor(section.depth));
                commandBuilder.set(root + " #SectionGuideDepth1.Visible", depthLevel >= 1);
                commandBuilder.set(root + " #SectionGuideDepth2.Visible", depthLevel >= 2);
                commandBuilder.set(root + " #SectionToggleTopButton.Visible", depthLevel == 0);
                commandBuilder.set(root + " #SectionToggleNestedButton.Visible", depthLevel == 1);
                commandBuilder.set(root + " #SectionToggleDeepButton.Visible", depthLevel >= 2);
                commandBuilder.set(root + " #SectionToggleTopLabel.Text", toggleText);
                commandBuilder.set(root + " #SectionToggleNestedLabel.Text", toggleText);
                commandBuilder.set(root + " #SectionToggleDeepLabel.Text", toggleText);
                commandBuilder.set(root + " #SectionToggleTopCount.Text", countText);
                commandBuilder.set(root + " #SectionToggleNestedCount.Text", countText);
                commandBuilder.set(root + " #SectionToggleDeepCount.Text", countText);
                commandBuilder.set(root + " #SectionToggleTopDirtyBadge.Visible", false);
                commandBuilder.set(root + " #SectionToggleNestedDirtyBadge.Visible", false);
                commandBuilder.set(root + " #SectionToggleDeepDirtyBadge.Visible", false);
                commandBuilder.set(root + " #SectionToggleTopDirtyBadgeText.Visible", false);
                commandBuilder.set(root + " #SectionToggleNestedDirtyBadgeText.Visible", false);
                commandBuilder.set(root + " #SectionToggleDeepDirtyBadgeText.Visible", false);
                commandBuilder.set(root + " #SectionToggleTopDirtyBadgeCautionIcon.Visible", hasStagedEdits);
                commandBuilder.set(root + " #SectionToggleNestedDirtyBadgeCautionIcon.Visible", hasStagedEdits);
                commandBuilder.set(root + " #SectionToggleDeepDirtyBadgeCautionIcon.Visible", hasStagedEdits);
                commandBuilder.set(root + " #SectionToggleTopDirtyBadgeCheckIcon.Visible", hasAppliedLocal);
                commandBuilder.set(root + " #SectionToggleNestedDirtyBadgeCheckIcon.Visible", hasAppliedLocal);
                commandBuilder.set(root + " #SectionToggleDeepDirtyBadgeCheckIcon.Visible", hasAppliedLocal);
                commandBuilder.set(root + " #SectionToggleTopRightBadge.Visible", showBadge);
                commandBuilder.set(root + " #SectionToggleNestedRightBadge.Visible", showBadge);
                commandBuilder.set(root + " #SectionToggleDeepRightBadge.Visible", showBadge);
                commandBuilder.set(root + " #SectionToggleTopRightBadgeCautionIcon.Visible", hasStagedEdits);
                commandBuilder.set(root + " #SectionToggleNestedRightBadgeCautionIcon.Visible", hasStagedEdits);
                commandBuilder.set(root + " #SectionToggleDeepRightBadgeCautionIcon.Visible", hasStagedEdits);
                commandBuilder.set(root + " #SectionToggleTopRightBadgeCheckIcon.Visible", hasAppliedLocal);
                commandBuilder.set(root + " #SectionToggleNestedRightBadgeCheckIcon.Visible", hasAppliedLocal);
                commandBuilder.set(root + " #SectionToggleDeepRightBadgeCheckIcon.Visible", hasAppliedLocal);
                commandBuilder.set(root + " #SectionToggleTopExpandedIcon.Visible", !collapsed);
                commandBuilder.set(root + " #SectionToggleTopCollapsedIcon.Visible", collapsed);
                commandBuilder.set(root + " #SectionToggleNestedExpandedIcon.Visible", !collapsed);
                commandBuilder.set(root + " #SectionToggleNestedCollapsedIcon.Visible", collapsed);
                commandBuilder.set(root + " #SectionToggleDeepExpandedIcon.Visible", !collapsed);
                commandBuilder.set(root + " #SectionToggleDeepCollapsedIcon.Visible", collapsed);

                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        root + " #SectionToggleTopButton",
                        EventData.of(K_ACTION, A_TOGGLE_SECTION).append(K_PATH, section.id),
                        false
                );
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        root + " #SectionToggleNestedButton",
                        EventData.of(K_ACTION, A_TOGGLE_SECTION).append(K_PATH, section.id),
                        false
                );
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        root + " #SectionToggleDeepButton",
                        EventData.of(K_ACTION, A_TOGGLE_SECTION).append(K_PATH, section.id),
                        false
                );
                continue;
            }

            FieldDef field = row.field;
            commandBuilder.append("#TwConfigPropertyRows", UI_FIELD_ROW);
            boolean overridden = TwConfigJsonUtil.hasPath(draft, field.path);
            JsonElement draftValue = TwConfigJsonUtil.getPath(draft, field.path);
            JsonElement diskValue = TwConfigJsonUtil.getPath(disk, field.path);
            boolean fieldHasStagedEdits = !jsonElementsEqual(draftValue, diskValue);
            boolean fieldHasAppliedLocal = !fieldHasStagedEdits && TwConfigJsonUtil.hasPath(disk, field.path);
            boolean fieldShowBadge = fieldHasStagedEdits || fieldHasAppliedLocal;
            JsonElement value = fieldValue(field, draft, effective, merged, descriptor);
            String textValue = fieldDisplayValue(field, value, descriptorKey);
            boolean hasBufferedInput = inputs(descriptorKey).containsKey(field.path);
            SourceBadge sourceBadge = sourceBadgeForField(field, descriptor, draft, merged);
            String host = root + " #FieldValueHost";
            int fieldDepthLevel = depthBucket(field.depth);
            String fieldLabel = labelForPath(field.path);

            commandBuilder.set(root + " #FieldRowBackground.Background", fieldBackgroundColor(field.depth));
            commandBuilder.set(root + " #FieldGuideDepth1.Visible", fieldDepthLevel >= 1);
            commandBuilder.set(root + " #FieldGuideDepth2.Visible", fieldDepthLevel >= 2);
            commandBuilder.set(root + " #FieldNameTop.Visible", fieldDepthLevel == 0);
            commandBuilder.set(root + " #FieldNameNested.Visible", fieldDepthLevel == 1);
            commandBuilder.set(root + " #FieldNameDeep.Visible", fieldDepthLevel >= 2);
            commandBuilder.set(root + " #FieldNameTop.Text", fieldLabel);
            commandBuilder.set(root + " #FieldNameNested.Text", fieldLabel);
            commandBuilder.set(root + " #FieldNameDeep.Text", fieldLabel);
            commandBuilder.set(root + " #FieldSourceChip.Text", sourceBadge.label());
            commandBuilder.set(root + " #FieldSourceChip.TooltipText", sourceBadge.tooltip());
            commandBuilder.set(root + " #FieldSourceChip.Visible", true);
            commandBuilder.set(root + " #FieldStateBadge.Visible", fieldShowBadge);
            commandBuilder.set(root + " #FieldStateBadgeCautionIcon.Visible", fieldHasStagedEdits);
            commandBuilder.set(root + " #FieldStateBadgeCheckIcon.Visible", fieldHasAppliedLocal);
            commandBuilder.set(root + " #FieldResetButton.Visible", !field.handoffOnly && overridden);
            commandBuilder.set(host + " #FieldCheckBox.Visible", field.kind == FieldKind.BOOLEAN && !field.handoffOnly);
            commandBuilder.set(host + " #FieldTextInput.Visible", (field.kind == FieldKind.STRING || field.kind == FieldKind.INTEGER || field.kind == FieldKind.DOUBLE) && !field.handoffOnly);
            commandBuilder.set(host + " #FieldDropdown.Visible", field.kind == FieldKind.OPTION && !field.handoffOnly);
            commandBuilder.set(host + " #FieldHandoff.Visible", field.handoffOnly || field.kind == FieldKind.HANDOFF);

            if (field.kind == FieldKind.BOOLEAN && !field.handoffOnly) {
                commandBuilder.set(host + " #FieldCheckBox.Value", parseBooleanSafe(textValue));
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.ValueChanged,
                        host + " #FieldCheckBox",
                        EventData.of(K_ACTION, A_SET_VALUE).append(K_PATH, field.path).append(K_FIELD_VALUE, root + " #FieldCheckBox.Value"),
                        false
                );
            } else if ((field.kind == FieldKind.STRING || field.kind == FieldKind.INTEGER || field.kind == FieldKind.DOUBLE) && !field.handoffOnly) {
                String inputValue = (overridden || hasBufferedInput) ? textValue : "";
                String placeholder = overridden ? tr("tamework.ui.configEditor.valuePlaceholder") : inheritedPlaceholderText(textValue);
                commandBuilder.set(host + " #FieldTextInput.Value", inputValue);
                commandBuilder.set(host + " #FieldTextInput.PlaceholderText", placeholder);
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.ValueChanged,
                        host + " #FieldTextInput",
                        EventData.of(K_ACTION, A_SET_VALUE).append(K_PATH, field.path).append(K_FIELD_VALUE, root + " #FieldTextInput.Value"),
                        false
                );
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Validating,
                        host + " #FieldTextInput",
                        EventData.of(K_ACTION, A_SET_VALUE).append(K_PATH, field.path).append(K_FIELD_VALUE, root + " #FieldTextInput.Value"),
                        false
                );
            } else if (field.kind == FieldKind.OPTION && !field.handoffOnly) {
                List<String> options = optionsFor(field, descriptor);
                commandBuilder.set(host + " #FieldDropdown.Entries", toDropdownEntries(options));
                commandBuilder.set(host + " #FieldDropdown.Value", normalizeOptionValue(textValue, options));
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.ValueChanged,
                        host + " #FieldDropdown",
                        EventData.of(K_ACTION, A_SET_VALUE).append(K_PATH, field.path).append(K_FIELD_VALUE, root + " #FieldDropdown.Value"),
                        false
                );
            } else {
                commandBuilder.set(host + " #FieldHandoff.Text", tr("tamework.ui.configEditor.field.handoffPath", field.path));
            }

            if (!field.handoffOnly) {
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        root + " #FieldResetButton",
                        EventData.of(K_ACTION, A_RESET).append(K_PATH, field.path),
                        false
                );
            }
        }
    }

    @Nonnull
    private List<RowDef> visibleRows(@Nonnull TwConfigAssetDescriptor descriptor) {
        String search = trim(propertyFilter).toLowerCase(Locale.ROOT);
        boolean hasSearch = !search.isBlank();
        ArrayList<RowDef> out = new ArrayList<>();
        for (RowDef row : LAYOUT) {
            if (row.section != null) {
                SectionDef section = row.section;
                if (!hasSearch && isAnyAncestorCollapsed(section.parentId)) {
                    continue;
                }
                if (!hasSearch || section.label.toLowerCase(Locale.ROOT).contains(search) || sectionMatches(section.id, search)) {
                    out.add(row);
                }
                continue;
            }
            FieldDef field = row.field;
            if (hasSearch && !matchesSearch(field, search)) {
                continue;
            }
            if (!hasSearch && isFieldCollapsed(field)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    private boolean sectionMatches(@Nonnull String sectionId, @Nonnull String search) {
        for (FieldDef field : FIELDS.values()) {
            if (field.sectionId == null) {
                continue;
            }
            if (!isSectionOrAncestor(field.sectionId, sectionId)) {
                continue;
            }
            if (matchesSearch(field, search)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSearch(@Nonnull FieldDef field, @Nonnull String search) {
        if (search.isBlank()) {
            return true;
        }
        String label = labelForPath(field.path).toLowerCase(Locale.ROOT);
        if (label.contains(search) || field.path.toLowerCase(Locale.ROOT).contains(search)) {
            return true;
        }
        SectionDef section = field.sectionId == null ? null : SECTIONS.get(field.sectionId);
        while (section != null) {
            if (section.label.toLowerCase(Locale.ROOT).contains(search)) {
                return true;
            }
            section = section.parentId == null ? null : SECTIONS.get(section.parentId);
        }
        return false;
    }

    private boolean isSectionOrAncestor(@Nonnull String sectionId, @Nonnull String targetSectionId) {
        String current = sectionId;
        while (current != null) {
            if (current.equalsIgnoreCase(targetSectionId)) {
                return true;
            }
            SectionDef section = SECTIONS.get(current);
            current = section == null ? null : section.parentId;
        }
        return false;
    }

    private boolean isFieldCollapsed(@Nonnull FieldDef field) {
        String current = field.sectionId;
        while (current != null) {
            if (collapsedSections.getOrDefault(current, false)) {
                return true;
            }
            SectionDef section = SECTIONS.get(current);
            current = section == null ? null : section.parentId;
        }
        return false;
    }

    private boolean isAnyAncestorCollapsed(@Nullable String sectionId) {
        String current = sectionId;
        while (current != null) {
            if (collapsedSections.getOrDefault(current, false)) {
                return true;
            }
            SectionDef section = SECTIONS.get(current);
            current = section == null ? null : section.parentId;
        }
        return false;
    }

    private void toggleSection(@Nullable String sectionId) {
        String key = trim(sectionId);
        if (!collapsedSections.containsKey(key)) {
            return;
        }
        collapsedSections.put(key, !collapsedSections.getOrDefault(key, false));
    }

    private void resetField(@Nullable String path) {
        FieldDef field = FIELDS.get(trim(path));
        if (field == null || field.handoffOnly) {
            return;
        }
        TwConfigAssetDescriptor descriptor = selectedDescriptor();
        if (descriptor == null) {
            return;
        }
        String key = descriptor.descriptorKey();
        TwConfigJsonUtil.removePath(draft(key), field.path);
        clearFieldInputState(key, field.path);
        statusLine = tr("tamework.ui.configEditor.status.resetToInherited", labelForPath(field.path));
        warningLine = "";
    }

    private void stageValue(@Nullable String path, @Nullable String rawValue) {
        FieldDef field = FIELDS.get(trim(path));
        if (field == null || field.handoffOnly) {
            return;
        }
        if (looksLikeSelectorExpression(rawValue)) {
            return;
        }
        TwConfigAssetDescriptor descriptor = selectedDescriptor();
        if (descriptor == null) {
            return;
        }
        String key = descriptor.descriptorKey();
        LinkedHashMap<String, String> input = inputs(key);
        LinkedHashMap<String, String> errors = errors(key);
        String raw = rawValue == null ? "" : rawValue;
        input.put(field.path, raw);
        try {
            JsonElement parsed = parseInput(field, raw, optionsFor(field, descriptor));
            if (parsed == null) {
                TwConfigJsonUtil.removePath(draft(key), field.path);
                errors.remove(field.path);
                statusLine = tr("tamework.ui.configEditor.status.setToInherited", labelForPath(field.path));
                warningLine = "";
                return;
            }
            TwConfigJsonUtil.setPath(draft(key), field.path, parsed);
            errors.remove(field.path);
            if (field.kind != FieldKind.STRING) {
                input.remove(field.path);
            }
            statusLine = tr("tamework.ui.configEditor.status.staged", labelForPath(field.path));
            warningLine = "";
        } catch (IllegalArgumentException ex) {
            errors.put(field.path, ex.getMessage());
            warningLine = ex.getMessage();
            statusLine = "";
        }
    }

    private static boolean shouldRefreshAfterSetValue(@Nullable FieldDef field) {
        if (field == null) {
            return true;
        }
        return field.kind == FieldKind.BOOLEAN || field.kind == FieldKind.OPTION;
    }

    @Nullable
    private JsonElement parseInput(@Nonnull FieldDef field, @Nonnull String raw, @Nonnull List<String> options) {
        String trimmed = raw.trim();
        return switch (field.kind) {
            case STRING -> new JsonPrimitive(raw);
            case BOOLEAN -> new JsonPrimitive(parseBooleanStrict(trimmed));
            case INTEGER -> {
                if (trimmed.isBlank()) {
                    throw new IllegalArgumentException(tr("tamework.ui.configEditor.validation.mustBeInteger", labelForPath(field.path)));
                }
                try {
                    yield new JsonPrimitive(Integer.parseInt(trimmed));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(tr("tamework.ui.configEditor.validation.mustBeInteger", labelForPath(field.path)));
                }
            }
            case DOUBLE -> {
                if (trimmed.isBlank()) {
                    throw new IllegalArgumentException(tr("tamework.ui.configEditor.validation.mustBeNumber", labelForPath(field.path)));
                }
                try {
                    double parsed = Double.parseDouble(trimmed);
                    if (!Double.isFinite(parsed)) {
                        throw new NumberFormatException("non-finite");
                    }
                    yield new JsonPrimitive(parsed);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(tr("tamework.ui.configEditor.validation.mustBeNumber", labelForPath(field.path)));
                }
            }
            case OPTION -> {
                if ("Parent".equalsIgnoreCase(field.path)
                        && (trimmed.isBlank() || PARENT_NONE_VALUE.equalsIgnoreCase(trimmed))) {
                    yield null;
                }
                if (trimmed.isBlank()) {
                    throw new IllegalArgumentException(tr("tamework.ui.configEditor.validation.mustBeSelected", labelForPath(field.path)));
                }
                yield new JsonPrimitive(normalizeOptionValue(trimmed, options));
            }
            case HANDOFF -> null;
        };
    }

    @Nullable
    private JsonElement fieldValue(@Nonnull FieldDef field,
                                   @Nonnull JsonObject draft,
                                   @Nonnull JsonObject effective,
                                   @Nonnull JsonObject merged,
                                   @Nonnull TwConfigAssetDescriptor descriptor) {
        JsonElement local = TwConfigJsonUtil.getPath(draft, field.path);
        if (local != null) {
            return local;
        }
        if ("Parent".equalsIgnoreCase(field.path)) {
            JsonElement mergedParent = TwConfigJsonUtil.getPath(merged, "Parent");
            if (mergedParent != null) {
                return mergedParent;
            }
            if (descriptor.parentAssetId() != null && !descriptor.parentAssetId().isBlank()) {
                return new JsonPrimitive(descriptor.parentAssetId());
            }
            return null;
        }
        JsonElement inherited = TwConfigJsonUtil.getPath(effective, field.path);
        if (inherited != null) {
            return inherited;
        }
        JsonElement mergedValue = TwConfigJsonUtil.getPath(merged, field.path);
        if (mergedValue != null) {
            return mergedValue;
        }
        JsonElement fallback = INHERITED_FALLBACK_VALUES.get(field.path);
        return fallback == null ? null : fallback.deepCopy();
    }

    @Nonnull
    private String fieldDisplayValue(@Nonnull FieldDef field, @Nullable JsonElement value, @Nonnull String descriptorKey) {
        String buffered = inputs(descriptorKey).get(field.path);
        if (buffered != null) {
            return buffered;
        }
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!value.isJsonPrimitive()) {
            return value.toString();
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean() ? "true" : "false";
        }
        if (primitive.isNumber()) {
            return field.kind == FieldKind.INTEGER
                    ? String.valueOf(primitive.getAsInt())
                    : String.valueOf(primitive.getAsDouble());
        }
        return primitive.getAsString();
    }

    @Nonnull
    private List<String> optionsFor(@Nonnull FieldDef field, @Nonnull TwConfigAssetDescriptor descriptor) {
        if ("Parent".equalsIgnoreCase(field.path)) {
            LinkedHashSet<String> parentAssetIds = new LinkedHashSet<>();
            for (TwConfigAssetDescriptor candidate : descriptorByKey.values()) {
                if (candidate.assetId().equalsIgnoreCase(descriptor.assetId())) {
                    continue;
                }
                parentAssetIds.add(candidate.assetId());
            }
            ArrayList<String> sorted = new ArrayList<>(parentAssetIds);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            ArrayList<String> options = new ArrayList<>(sorted.size() + 1);
            options.add(PARENT_NONE_VALUE);
            options.addAll(sorted);
            return List.copyOf(options);
        }
        return field.options;
    }

    @Nullable
    private String resolveDescriptorKey(@Nullable String selectedValue) {
        String raw = trim(selectedValue);
        if (raw.isBlank() || looksLikeSelectorExpression(raw)) {
            return null;
        }
        TwConfigAssetDescriptor direct = descriptorByKey.get(raw);
        if (direct != null) {
            return direct.descriptorKey();
        }
        for (TwConfigAssetDescriptor descriptor : descriptorByKey.values()) {
            if (descriptor.descriptorKey().equalsIgnoreCase(raw)) {
                return descriptor.descriptorKey();
            }
        }
        for (TwConfigAssetDescriptor descriptor : descriptorByKey.values()) {
            if (descriptor.assetId().equalsIgnoreCase(raw)) {
                return descriptor.descriptorKey();
            }
        }
        int bracket = raw.indexOf('[');
        if (bracket > 0) {
            String assetId = raw.substring(0, bracket).trim();
            for (TwConfigAssetDescriptor descriptor : descriptorByKey.values()) {
                if (descriptor.assetId().equalsIgnoreCase(assetId)) {
                    return descriptor.descriptorKey();
                }
            }
        }
        return null;
    }

    @Nonnull
    private JsonObject effectiveJson(@Nonnull String descriptorKey, @Nonnull Set<String> visited) {
        if (!visited.add(descriptorKey)) {
            return mergedCurrentJson(descriptorKey);
        }
        TwConfigAssetDescriptor descriptor = descriptorByKey.get(descriptorKey);
        if (descriptor == null) {
            return new JsonObject();
        }
        JsonObject ownMerged = mergedCurrentJson(descriptorKey);
        String parentId = parentId(ownMerged, descriptor);
        if (parentId == null || parentId.isBlank()) {
            return ownMerged;
        }
        TwConfigAssetDescriptor parent = findParent(descriptor, parentId);
        if (parent == null) {
            return ownMerged;
        }
        return TwConfigJsonUtil.merge(effectiveJson(parent.descriptorKey(), visited), ownMerged);
    }

    @Nonnull
    private JsonObject mergedCurrentJson(@Nonnull String descriptorKey) {
        return TwConfigJsonUtil.merge(TwConfigJsonUtil.copyObject(sourceByKey.get(descriptorKey)), TwConfigJsonUtil.copyObject(draftByKey.get(descriptorKey)));
    }

    @Nullable
    private String parentId(@Nonnull JsonObject merged, @Nonnull TwConfigAssetDescriptor descriptor) {
        JsonElement parent = TwConfigJsonUtil.getPath(merged, "Parent");
        if (parent != null && parent.isJsonPrimitive() && parent.getAsJsonPrimitive().isString()) {
            String value = parent.getAsString();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return descriptor.parentAssetId();
    }

    @Nullable
    private TwConfigAssetDescriptor findParent(@Nonnull TwConfigAssetDescriptor descriptor, @Nonnull String parentAssetId) {
        for (TwConfigAssetDescriptor candidate : descriptorByKey.values()) {
            if (!candidate.assetId().equalsIgnoreCase(parentAssetId)) {
                continue;
            }
            if (candidate.sourcePackKey().equalsIgnoreCase(descriptor.sourcePackKey())) {
                return candidate;
            }
        }
        for (TwConfigAssetDescriptor candidate : descriptorByKey.values()) {
            if (candidate.assetId().equalsIgnoreCase(parentAssetId)) {
                return candidate;
            }
        }
        return null;
    }

    @Nonnull
    private SourceBadge sourceBadgeForField(@Nonnull FieldDef field,
                                            @Nonnull TwConfigAssetDescriptor descriptor,
                                            @Nonnull JsonObject draft,
                                            @Nonnull JsonObject merged) {
        if (field.handoffOnly || field.kind == FieldKind.HANDOFF) {
            return new SourceBadge(
                    tr("tamework.ui.configEditor.source.handoff.label"),
                    tr("tamework.ui.configEditor.source.handoff.tooltip", field.path)
            );
        }
        if (TwConfigJsonUtil.hasPath(draft, field.path)) {
            return new SourceBadge(
                    tr("tamework.ui.configEditor.source.local.label"),
                    tr("tamework.ui.configEditor.source.local.tooltip", descriptor.assetId())
            );
        }
        if ("Parent".equalsIgnoreCase(field.path)) {
            JsonElement explicitParent = TwConfigJsonUtil.getPath(merged, "Parent");
            if (explicitParent != null && explicitParent.isJsonPrimitive()) {
                String value = explicitParent.getAsString();
                if (value != null && !value.isBlank()) {
                    return new SourceBadge(
                            tr("tamework.ui.configEditor.source.asset.label"),
                            tr("tamework.ui.configEditor.source.parentDefinedOnAsset", value.trim())
                    );
                }
            }
            if (descriptor.parentAssetId() != null && !descriptor.parentAssetId().isBlank()) {
                return new SourceBadge(
                        tr("tamework.ui.configEditor.source.asset.label"),
                        tr("tamework.ui.configEditor.source.parentFromMetadata", descriptor.parentAssetId().trim())
                );
            }
            return new SourceBadge(
                    tr("tamework.ui.configEditor.source.default.label"),
                    tr("tamework.ui.configEditor.source.noParentConfigured")
            );
        }
        if (TwConfigJsonUtil.hasPath(merged, field.path)) {
            return new SourceBadge(
                    tr("tamework.ui.configEditor.source.asset.label"),
                    tr("tamework.ui.configEditor.source.definedOnAsset", descriptor.assetId())
            );
        }

        HashSet<String> visited = new HashSet<>();
        visited.add(descriptor.descriptorKey());
        TwConfigAssetDescriptor current = descriptor;
        int ancestorDepth = 0;
        while (current != null) {
            String parentId = parentId(mergedCurrentJson(current.descriptorKey()), current);
            if (parentId == null || parentId.isBlank()) {
                break;
            }
            TwConfigAssetDescriptor parent = findParent(current, parentId);
            if (parent == null) {
                String tier = ancestorDepth == 0
                        ? tr("tamework.ui.configEditor.source.parent.label")
                        : tr("tamework.ui.configEditor.source.ancestor.label");
                return new SourceBadge(tier, tr("tamework.ui.configEditor.source.assetNotFound", tier, parentId));
            }
            if (!visited.add(parent.descriptorKey())) {
                return new SourceBadge(
                        tr("tamework.ui.configEditor.source.ancestor.label"),
                        tr("tamework.ui.configEditor.source.inheritanceLoop", parent.assetId())
                );
            }
            if (TwConfigJsonUtil.hasPath(mergedCurrentJson(parent.descriptorKey()), field.path)) {
                String tier = ancestorDepth == 0
                        ? tr("tamework.ui.configEditor.source.parent.label")
                        : tr("tamework.ui.configEditor.source.ancestor.label");
                return new SourceBadge(
                        tier,
                        tr("tamework.ui.configEditor.source.parentSource", tier, parent.assetId(), shortPackLabel(parent.sourcePackKey()))
                );
            }
            current = parent;
            ancestorDepth++;
        }

        if (INHERITED_FALLBACK_VALUES.containsKey(field.path)) {
            return new SourceBadge(
                    tr("tamework.ui.configEditor.source.default.label"),
                    tr("tamework.ui.configEditor.source.runtimeDefault")
            );
        }
        return new SourceBadge(
                tr("tamework.ui.configEditor.source.default.label"),
                tr("tamework.ui.configEditor.source.resolvedDefault")
        );
    }

    @Nonnull
    private String inheritanceChainText(@Nonnull TwConfigAssetDescriptor descriptor) {
        ArrayList<String> chain = new ArrayList<>();
        chain.add(descriptor.assetId());
        HashSet<String> visited = new HashSet<>();
        visited.add(descriptor.descriptorKey());

        TwConfigAssetDescriptor current = descriptor;
        for (int i = 0; i < 12 && current != null; i++) {
            String parentId = parentId(mergedCurrentJson(current.descriptorKey()), current);
            if (parentId == null || parentId.isBlank()) {
                break;
            }
            TwConfigAssetDescriptor parent = findParent(current, parentId);
            if (parent == null) {
                chain.add(tr("tamework.ui.configEditor.chain.node.missing", parentId));
                break;
            }
            if (!visited.add(parent.descriptorKey())) {
                chain.add(tr("tamework.ui.configEditor.chain.node.loop", parent.assetId()));
                break;
            }
            chain.add(parent.assetId());
            current = parent;
        }

        if (chain.size() <= 1) {
            return tr("tamework.ui.configEditor.chain.noParent");
        }
        return tr("tamework.ui.configEditor.chain.value", String.join(" -> ", chain));
    }

    private int pendingDraftFileCount() {
        int count = 0;
        for (TwConfigAssetDescriptor descriptor : descriptorByKey.values()) {
            String key = descriptor.descriptorKey();
            if (!TwConfigJsonUtil.copyObject(draftByKey.get(key)).equals(TwConfigJsonUtil.copyObject(diskByKey.get(key)))) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    private String firstValidationError() {
        for (Map<String, String> errors : validationByDescriptor.values()) {
            if (errors == null || errors.isEmpty()) {
                continue;
            }
            for (String value : errors.values()) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    @Nonnull
    private String resolvedWarningLine() {
        if (warningLine != null && !warningLine.isBlank()) {
            return warningLine;
        }
        String validation = firstValidationError();
        if (validation != null) {
            return validation;
        }
        TwConfigAssetDescriptor selected = selectedDescriptor();
        if (selected == null) {
            return "";
        }
        ArrayList<String> unknown = new ArrayList<>();
        JsonObject draft = draft(selected.descriptorKey());
        for (Map.Entry<String, JsonElement> entry : draft.entrySet()) {
            if (!KNOWN_TOP_LEVEL_KEYS.contains(entry.getKey())) {
                unknown.add(entry.getKey());
            }
        }
        unknown.sort(String.CASE_INSENSITIVE_ORDER);
        return unknown.isEmpty() ? "" : tr("tamework.ui.configEditor.warning.unknownOverrideKeys", String.join(", ", unknown));
    }

    @Nonnull
    private List<DropdownEntryInfo> assetDropdownEntries() {
        List<TwConfigAssetDescriptor> filtered = filteredDescriptors();
        if (filtered.isEmpty()) {
            return List.of();
        }
        ArrayList<DropdownEntryInfo> entries = new ArrayList<>(filtered.size());
        for (TwConfigAssetDescriptor descriptor : filtered) {
            String label = descriptor.assetId() + " [" + shortPackLabel(descriptor.sourcePackKey()) + "]";
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(label), descriptor.descriptorKey()));
        }
        return List.copyOf(entries);
    }

    @Nonnull
    private List<TwConfigAssetDescriptor> filteredDescriptors() {
        String search = trim(assetFilter).toLowerCase(Locale.ROOT);
        boolean hasSearch = !search.isBlank();
        ArrayList<TwConfigAssetDescriptor> filtered = new ArrayList<>();
        for (TwConfigAssetDescriptor descriptor : descriptorByKey.values()) {
            if (!hasSearch) {
                filtered.add(descriptor);
                continue;
            }
            if (descriptor.assetId().toLowerCase(Locale.ROOT).contains(search)
                    || descriptor.sourcePackKey().toLowerCase(Locale.ROOT).contains(search)) {
                filtered.add(descriptor);
            }
        }
        filtered.sort(Comparator.comparing(TwConfigAssetDescriptor::assetId, String.CASE_INSENSITIVE_ORDER));
        return filtered;
    }

    @Nullable
    private TwConfigAssetDescriptor selectedDescriptor() {
        if (selectedDescriptorKey == null || selectedDescriptorKey.isBlank()) {
            return null;
        }
        return descriptorByKey.get(selectedDescriptorKey);
    }

    @Nonnull
    private JsonObject draft(@Nonnull String descriptorKey) {
        return draftByKey.computeIfAbsent(descriptorKey, key -> new JsonObject());
    }

    @Nonnull
    private JsonObject disk(@Nonnull String descriptorKey) {
        return diskByKey.computeIfAbsent(descriptorKey, key -> new JsonObject());
    }

    @Nonnull
    private LinkedHashMap<String, String> inputs(@Nonnull String descriptorKey) {
        return inputByDescriptor.computeIfAbsent(descriptorKey, key -> new LinkedHashMap<>());
    }

    @Nonnull
    private LinkedHashMap<String, String> errors(@Nonnull String descriptorKey) {
        return validationByDescriptor.computeIfAbsent(descriptorKey, key -> new LinkedHashMap<>());
    }

    private void clearFieldInputState(@Nonnull String descriptorKey, @Nonnull String path) {
        inputs(descriptorKey).remove(path);
        errors(descriptorKey).remove(path);
    }

    @Nonnull
    private String tr(@Nonnull String key, Object... args) {
        return LocalizedText.format(playerRef, key, args);
    }

    @Nonnull
    private static String labelForPath(@Nonnull String path) {
        String leaf = path;
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            leaf = path.substring(dot + 1);
        }
        return humanize(leaf);
    }

    @Nonnull
    private static String humanize(@Nonnull String value) {
        String spaced = value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isBlank()) {
            return value;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Nonnull
    private static String sanitizeFilter(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    @Nonnull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Nonnull
    private static String shortPackLabel(@Nullable String sourcePackKey) {
        if (sourcePackKey == null || sourcePackKey.isBlank()) {
            return LocalizedText.resolve((String) null, "tamework.ui.configEditor.pack.unknown");
        }
        String normalized = sourcePackKey.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash < normalized.length() - 1 ? normalized.substring(slash + 1) : normalized;
    }

    private static boolean looksLikeSelectorExpression(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank() || !trimmed.startsWith("#")) {
            return false;
        }
        return trimmed.contains(".Value")
                || trimmed.contains(".Color")
                || trimmed.contains(".Text")
                || trimmed.contains(" #");
    }

    private static boolean parseBooleanSafe(@Nullable String value) {
        try {
            return parseBooleanStrict(trim(value));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean parseBooleanStrict(@Nonnull String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off", "" -> false;
            default -> throw new IllegalArgumentException(
                    LocalizedText.resolve((String) null, "tamework.ui.configEditor.validation.booleanTrueFalse")
            );
        };
    }

    @Nonnull
    private static String normalizeOptionValue(@Nullable String candidate, @Nonnull List<String> options) {
        if (candidate == null || candidate.isBlank()) {
            return options.isEmpty() ? "" : options.get(0);
        }
        String trimmed = candidate.trim();
        for (String option : options) {
            if (option != null && option.equalsIgnoreCase(trimmed)) {
                return option;
            }
        }
        return trimmed;
    }

    @Nonnull
    private String inheritedPlaceholderText(@Nullable String value) {
        String raw = value == null ? "" : value;
        return raw.isBlank() ? tr("tamework.ui.configEditor.valuePlaceholder") : raw;
    }

    @Nonnull
    private List<DropdownEntryInfo> toDropdownEntries(@Nonnull List<String> options) {
        if (options.isEmpty()) {
            return List.of();
        }
        ArrayList<DropdownEntryInfo> out = new ArrayList<>(options.size());
        for (String option : options) {
            String label = PARENT_NONE_VALUE.equals(option)
                    ? tr("tamework.ui.configEditor.dropdown.inherit")
                    : option;
            out.add(new DropdownEntryInfo(LocalizableString.fromString(label), option));
        }
        return List.copyOf(out);
    }

    private int sectionFieldCount(@Nonnull String sectionId) {
        int count = 0;
        for (FieldDef field : FIELDS.values()) {
            String current = field.sectionId;
            while (current != null) {
                if (current.equalsIgnoreCase(sectionId)) {
                    count++;
                    break;
                }
                SectionDef section = SECTIONS.get(current);
                current = section == null ? null : section.parentId;
            }
        }
        return count;
    }

    private boolean sectionHasAppliedOverrides(@Nonnull String sectionId, @Nonnull JsonObject disk) {
        for (FieldDef field : FIELDS.values()) {
            if (field.sectionId == null) {
                continue;
            }
            if (!isSectionOrAncestor(field.sectionId, sectionId)) {
                continue;
            }
            if (TwConfigJsonUtil.hasPath(disk, field.path)) {
                return true;
            }
        }
        return false;
    }

    private boolean sectionHasStagedOverrides(@Nonnull String sectionId, @Nonnull JsonObject draft, @Nonnull JsonObject disk) {
        for (FieldDef field : FIELDS.values()) {
            if (field.sectionId == null) {
                continue;
            }
            if (!isSectionOrAncestor(field.sectionId, sectionId)) {
                continue;
            }
            JsonElement draftValue = TwConfigJsonUtil.getPath(draft, field.path);
            JsonElement diskValue = TwConfigJsonUtil.getPath(disk, field.path);
            if (!jsonElementsEqual(draftValue, diskValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean jsonElementsEqual(@Nullable JsonElement a, @Nullable JsonElement b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    @Nonnull
    private String formatFieldCount(int count) {
        int clamped = Math.max(0, count);
        return clamped == 1
                ? tr("tamework.ui.configEditor.fieldCount.single", clamped)
                : tr("tamework.ui.configEditor.fieldCount.plural", clamped);
    }

    @Nonnull
    private static Map<String, JsonElement> buildInheritedFallbackValues() {
        TwGlobalConfig defaults = TwGlobalConfig.defaultConfig();
        LinkedHashMap<String, JsonElement> out = new LinkedHashMap<>();

        out.put("General.Enabled", new JsonPrimitive(defaults.isEnabled()));
        out.put("General.Priority", new JsonPrimitive(defaults.getPriority()));

        out.put("OwnershipProtection.BlockOwnerDamage", new JsonPrimitive(defaults.isBlockOwnerDamage()));
        out.put("OwnershipProtection.BlockAllPlayerDamageIfOwned", new JsonPrimitive(defaults.isBlockAllPlayerDamageIfOwned()));
        out.put("OwnershipProtection.InvulnerableIfOwned", new JsonPrimitive(defaults.isInvulnerableIfOwned()));

        out.put("InteractionDefaults.InteractionConfigParam", new JsonPrimitive(defaults.getInteractionConfigParam()));
        out.put("InteractionDefaults.LovedItemsParam", new JsonPrimitive(defaults.getLovedItemsParam()));
        out.put("InteractionDefaults.IsHarvestableParam", new JsonPrimitive(defaults.getIsHarvestableParam()));
        out.put("InteractionDefaults.IsMountableParam", new JsonPrimitive(defaults.getIsMountableParam()));
        out.put("InteractionDefaults.HarvestContextParam", new JsonPrimitive(defaults.getHarvestContextParam()));
        out.put("InteractionDefaults.HarvestAlarmName", new JsonPrimitive(defaults.getHarvestAlarmName()));
        out.put("InteractionDefaults.InteractionCooldownAlarmPrefix", new JsonPrimitive(defaults.getInteractionCooldownAlarmPrefix()));

        out.put("Command.ReturnHomeTeleportDistance", new JsonPrimitive(defaults.getCommandReturnHomeTeleportDistance()));
        out.put("Command.ReturnHomePathDistanceBeforeTeleport", new JsonPrimitive(defaults.getCommandReturnHomePathDistanceBeforeTeleport()));
        out.put("Command.ReturnHomeTeleportDelayMs", new JsonPrimitive(defaults.getCommandReturnHomeTeleportDelayMs()));
        out.put("Command.RecallSafeSpawnDistance", new JsonPrimitive(defaults.getCommandRecallSafeSpawnDistance()));
        out.put("Command.RecallForceRelocateDistance", new JsonPrimitive(defaults.getCommandRecallForceRelocateDistance()));
        out.put("Command.RelocationRetryIntervalMs", new JsonPrimitive(defaults.getCommandRelocationRetryIntervalMs()));
        out.put("Command.RelocationMaxWaitMs", new JsonPrimitive(defaults.getCommandRelocationMaxWaitMs()));
        out.put("Command.RelocationMaxRetryAttempts", new JsonPrimitive(defaults.getCommandRelocationMaxRetryAttempts()));
        out.put("Command.DeadRespawnEnabled", new JsonPrimitive(defaults.isCommandDeadRespawnEnabled()));
        out.put("Command.DeadRespawnCooldownMs", new JsonPrimitive(defaults.getCommandDeadRespawnCooldownMs()));
        out.put("Command.DeadRespawnCooldownMins", new JsonPrimitive(defaults.getCommandDeadRespawnCooldownMs() / 60000.0d));
        out.put("Command.DeadRespawnFollowRetryDelayMs", new JsonPrimitive(defaults.getCommandDeadRespawnFollowRetryDelayMs()));
        out.put("Command.DeadRespawnDistanceClose", new JsonPrimitive(defaults.getCommandDeadRespawnDistanceClose()));
        out.put("Command.DeadRespawnDistanceNear", new JsonPrimitive(defaults.getCommandDeadRespawnDistanceNear()));
        out.put("Command.DeadRespawnDistanceMid", new JsonPrimitive(defaults.getCommandDeadRespawnDistanceMid()));
        out.put("Command.DeadRespawnDistanceFar", new JsonPrimitive(defaults.getCommandDeadRespawnDistanceFar()));
        out.put("Command.PlacementMinRelativeY", new JsonPrimitive(defaults.getCommandPlacementMinRelativeY()));
        out.put("Command.PlacementMaxRelativeY", new JsonPrimitive(defaults.getCommandPlacementMaxRelativeY()));
        out.put("Command.LinkedPanelRequireUnlinkConfirm", new JsonPrimitive(defaults.isCommandLinkedPanelRequireUnlinkConfirm()));

        out.put("AssetSets.TranquilizerShortbow", new JsonPrimitive(defaults.isTranquilizerShortbowAssetSetEnabled()));
        out.put("AssetSets.TranquilizerArrow", new JsonPrimitive(defaults.isTranquilizerArrowAssetSetEnabled()));
        out.put("AssetSets.TranquilizerPotion", new JsonPrimitive(defaults.isTranquilizerPotionAssetSetEnabled()));
        out.put("AssetSets.FeedTrough", new JsonPrimitive(defaults.isFeedTroughAssetSetEnabled()));

        out.put("Population.LimitPerPlayerOwnedTotal", new JsonPrimitive(defaults.getPopulationLimitPerPlayerOwnedTotal()));
        out.put("Population.PerPlayerLimitScope", new JsonPrimitive(defaults.getPopulationPerPlayerLimitScope().configValue()));

        out.put("SimpleClaims.SimpleClaimsEnabled", new JsonPrimitive(defaults.isSimpleClaimsEnabled()));
        out.put("SimpleClaims.Breeding.LimitPerClaimChunk", new JsonPrimitive(defaults.getSimpleClaimsBreedingLimitPerClaimChunk()));
        out.put("SimpleClaims.Breeding.LimitPerClaimTotal", new JsonPrimitive(defaults.getSimpleClaimsBreedingLimitPerClaimTotal()));
        out.put("SimpleClaims.Breeding.BreedingRequiresClaim", new JsonPrimitive(defaults.isSimpleClaimsBreedingRequiresClaim()));
        out.put("SimpleClaims.Damage.ProtectTamedFromNonMembers", new JsonPrimitive(defaults.isSimpleClaimsDamageProtectTamedFromNonMembers()));
        out.put("SimpleClaims.Damage.AllowDamagePermissionKey", new JsonPrimitive(defaults.getSimpleClaimsDamageAllowDamagePermissionKey()));

        return Collections.unmodifiableMap(out);
    }

    private static int depthBucket(int depth) {
        if (depth <= 0) {
            return 0;
        }
        if (depth == 1) {
            return 1;
        }
        return 2;
    }

    @Nonnull
    private static String sectionBackgroundColor(int depth) {
        return switch (depthBucket(depth)) {
            case 0 -> "#1a2b40";
            case 1 -> "#20354a";
            default -> "#26405a";
        };
    }

    @Nonnull
    private static String fieldBackgroundColor(int depth) {
        return switch (depthBucket(depth)) {
            case 0 -> "#22364a";
            case 1 -> "#274056";
            default -> "#2c4860";
        };
    }

    @Nonnull
    private static String shortenedPathForUi(@Nullable Path path) {
        if (path == null) {
            return "";
        }
        Path normalized = path.toAbsolutePath().normalize();
        ArrayList<String> segments = new ArrayList<>();
        for (Path segment : normalized) {
            if (segment == null) {
                continue;
            }
            String part = segment.toString();
            if (part == null || part.isBlank()) {
                continue;
            }
            segments.add(part);
        }
        if (segments.isEmpty()) {
            return normalized.toString();
        }

        int startIndex = -1;
        for (int i = 0; i < segments.size(); i++) {
            if ("server".equalsIgnoreCase(segments.get(i))) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            for (int i = 0; i < segments.size(); i++) {
                if ("universe".equalsIgnoreCase(segments.get(i))) {
                    startIndex = i;
                    break;
                }
            }
        }
        if (startIndex < 0) {
            startIndex = Math.max(0, segments.size() - 6);
        }

        StringBuilder out = new StringBuilder();
        if (startIndex > 0) {
            out.append("...\\");
        }
        for (int i = startIndex; i < segments.size(); i++) {
            if (i > startIndex) {
                out.append("\\");
            }
            out.append(normalizePathSegmentForUi(segments.get(i)));
        }
        return out.toString();
    }

    @Nonnull
    private static String normalizePathSegmentForUi(@Nonnull String segment) {
        if ("server".equalsIgnoreCase(segment)) {
            return "Server";
        }
        if ("universe".equalsIgnoreCase(segment)) {
            return "Universe";
        }
        if ("tamework".equalsIgnoreCase(segment)) {
            return "Tamework";
        }
        return segment;
    }

    private void refreshUi() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        bindStaticEvents(eventBuilder);
        render(commandBuilder, eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private enum FieldKind {
        STRING, INTEGER, DOUBLE, BOOLEAN, OPTION, HANDOFF
    }

    private record SectionDef(@Nonnull String id, @Nonnull String label, int depth, @Nullable String parentId) {
    }

    private record FieldDef(@Nonnull String path,
                            @Nonnull FieldKind kind,
                            @Nullable String sectionId,
                            int depth,
                            boolean handoffOnly,
                            @Nonnull List<String> options) {
    }

    private record RowDef(@Nullable SectionDef section, @Nullable FieldDef field) {
        static RowDef section(@Nonnull SectionDef section) {
            return new RowDef(section, null);
        }

        static RowDef field(@Nonnull FieldDef field) {
            return new RowDef(null, field);
        }
    }

    private record SourceBadge(@Nonnull String label, @Nonnull String tooltip) {
    }

    @Nonnull
    private static List<RowDef> buildLayout() {
        ArrayList<RowDef> rows = new ArrayList<>();
        rows.add(RowDef.field(new FieldDef("Parent", FieldKind.OPTION, null, 0, false, List.of())));
        rows.add(RowDef.field(new FieldDef("Comment", FieldKind.STRING, null, 0, false, List.of())));
        rows.add(RowDef.field(new FieldDef("Tags", FieldKind.HANDOFF, null, 0, true, List.of())));

        rows.add(RowDef.section(S_GENERAL));
        rows.add(RowDef.field(new FieldDef("General.Enabled", FieldKind.BOOLEAN, S_GENERAL.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("General.Priority", FieldKind.INTEGER, S_GENERAL.id, 1, false, List.of())));

        rows.add(RowDef.section(S_OWNERSHIP));
        rows.add(RowDef.field(new FieldDef("OwnershipProtection.BlockOwnerDamage", FieldKind.BOOLEAN, S_OWNERSHIP.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("OwnershipProtection.BlockAllPlayerDamageIfOwned", FieldKind.BOOLEAN, S_OWNERSHIP.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("OwnershipProtection.InvulnerableIfOwned", FieldKind.BOOLEAN, S_OWNERSHIP.id, 1, false, List.of())));

        rows.add(RowDef.section(S_INTERACTION));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.InteractionConfigParam", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.LovedItemsParam", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.IsHarvestableParam", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.IsMountableParam", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.HarvestContextParam", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.HarvestAlarmName", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("InteractionDefaults.InteractionCooldownAlarmPrefix", FieldKind.STRING, S_INTERACTION.id, 1, false, List.of())));

        rows.add(RowDef.section(S_COMMAND));
        for (String path : List.of(
                "Command.ReturnHomeTeleportDistance",
                "Command.ReturnHomePathDistanceBeforeTeleport",
                "Command.ReturnHomeTeleportDelayMs",
                "Command.RecallSafeSpawnDistance",
                "Command.RecallForceRelocateDistance",
                "Command.RelocationRetryIntervalMs",
                "Command.RelocationMaxWaitMs",
                "Command.RelocationMaxRetryAttempts",
                "Command.DeadRespawnEnabled",
                "Command.DeadRespawnCooldownMs",
                "Command.DeadRespawnCooldownMins",
                "Command.DeadRespawnFollowRetryDelayMs",
                "Command.DeadRespawnDistanceClose",
                "Command.DeadRespawnDistanceNear",
                "Command.DeadRespawnDistanceMid",
                "Command.DeadRespawnDistanceFar",
                "Command.PlacementMinRelativeY",
                "Command.PlacementMaxRelativeY",
                "Command.LinkedPanelRequireUnlinkConfirm")) {
            rows.add(RowDef.field(new FieldDef(path, commandFieldKind(path), S_COMMAND.id, 1, false, List.of())));
        }

        rows.add(RowDef.section(S_ASSET_SETS));
        rows.add(RowDef.field(new FieldDef("AssetSets.TranquilizerShortbow", FieldKind.BOOLEAN, S_ASSET_SETS.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("AssetSets.TranquilizerArrow", FieldKind.BOOLEAN, S_ASSET_SETS.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("AssetSets.TranquilizerPotion", FieldKind.BOOLEAN, S_ASSET_SETS.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("AssetSets.FeedTrough", FieldKind.BOOLEAN, S_ASSET_SETS.id, 1, false, List.of())));

        rows.add(RowDef.section(S_POPULATION));
        rows.add(RowDef.field(new FieldDef("Population.LimitPerPlayerOwnedTotal", FieldKind.INTEGER, S_POPULATION.id, 1, false, List.of())));
        rows.add(RowDef.field(new FieldDef("Population.PerPlayerLimitScope", FieldKind.OPTION, S_POPULATION.id, 1, false, List.of("PerWorld", "Global"))));

        rows.add(RowDef.section(S_SIMPLE));
        rows.add(RowDef.field(new FieldDef("SimpleClaims.SimpleClaimsEnabled", FieldKind.BOOLEAN, S_SIMPLE.id, 1, false, List.of())));
        rows.add(RowDef.section(S_SIMPLE_BREED));
        rows.add(RowDef.field(new FieldDef("SimpleClaims.Breeding.LimitPerClaimChunk", FieldKind.INTEGER, S_SIMPLE_BREED.id, 2, false, List.of())));
        rows.add(RowDef.field(new FieldDef("SimpleClaims.Breeding.LimitPerClaimTotal", FieldKind.INTEGER, S_SIMPLE_BREED.id, 2, false, List.of())));
        rows.add(RowDef.field(new FieldDef("SimpleClaims.Breeding.BreedingRequiresClaim", FieldKind.BOOLEAN, S_SIMPLE_BREED.id, 2, false, List.of())));
        rows.add(RowDef.section(S_SIMPLE_DAMAGE));
        rows.add(RowDef.field(new FieldDef("SimpleClaims.Damage.ProtectTamedFromNonMembers", FieldKind.BOOLEAN, S_SIMPLE_DAMAGE.id, 2, false, List.of())));
        rows.add(RowDef.field(new FieldDef("SimpleClaims.Damage.AllowDamagePermissionKey", FieldKind.STRING, S_SIMPLE_DAMAGE.id, 2, false, List.of())));

        return List.copyOf(rows);
    }

    private static FieldKind commandFieldKind(String path) {
        if (path.endsWith("Enabled") || path.endsWith("Confirm")) {
            return FieldKind.BOOLEAN;
        }
        if (path.endsWith("Ms") || path.endsWith("Attempts")) {
            return FieldKind.INTEGER;
        }
        return FieldKind.DOUBLE;
    }

    @Nonnull
    private static Map<String, FieldDef> buildFields() {
        LinkedHashMap<String, FieldDef> out = new LinkedHashMap<>();
        for (RowDef row : LAYOUT) {
            if (row.field != null) {
                out.put(row.field.path, row.field);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Nonnull
    private static Map<String, SectionDef> buildSections() {
        LinkedHashMap<String, SectionDef> out = new LinkedHashMap<>();
        for (RowDef row : LAYOUT) {
            if (row.section != null) {
                out.put(row.section.id, row.section);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Nonnull
    private static Set<String> buildKnownTopLevelKeys() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FieldDef field : FIELDS.values()) {
            String path = field.path;
            int dot = path.indexOf('.');
            out.add(dot < 0 ? path : path.substring(0, dot));
        }
        return Collections.unmodifiableSet(out);
    }

    public static final class EventPayload {
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .<String>append(new KeyedCodec<>(K_ACTION, Codec.STRING), (x, v) -> x.action = v, x -> x.action).add()
                .<String>append(new KeyedCodec<>(K_PATH, Codec.STRING), (x, v) -> x.path = v, x -> x.path).add()
                .<String>append(new KeyedCodec<>(K_FIELD_VALUE, Codec.STRING), (x, v) -> x.value = v, x -> x.value).add()
                .<String>append(new KeyedCodec<>(K_ASSET_KEY, Codec.STRING), (x, v) -> x.assetKey = v, x -> x.assetKey).add()
                .<String>append(new KeyedCodec<>(K_ASSET_FILTER, Codec.STRING), (x, v) -> x.assetFilter = v, x -> x.assetFilter).add()
                .<String>append(new KeyedCodec<>(K_PROPERTY_FILTER, Codec.STRING), (x, v) -> x.propertyFilter = v, x -> x.propertyFilter).add()
                .build();

        private String action;
        private String path;
        private String value;
        private String assetKey;
        private String assetFilter;
        private String propertyFilter;
    }
}
