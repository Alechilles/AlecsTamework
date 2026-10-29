package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwModSetting;
import com.alechilles.alecstamework.config.assets.TwModSettingType;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.mods.ModSettingsCatalog;
import com.alechilles.alecstamework.settings.mods.ModSettingsService;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Mod Settings tab of /tw settings: a row for every setting that a mod declares under
 * Server/Tamework/ModSettings, and the edits that wait for the page's Apply button.
 *
 * <p>World thread only, like the page that owns it. Number fields keep the typed text; the setting
 * coerces (and clamps) it when Apply collects the edits, so invalid text never reaches the service.</p>
 */
final class TameworkSettingsModTab {
    /** Page event actions and payload keys; the page routes them here. */
    static final String ACTION_BOOL = "ModSettingBool";
    static final String ACTION_CHOICE = "ModSettingChoice";
    static final String ACTION_TEXT = "ModSettingText";
    static final String ACTION_RESET = "ModSettingReset";
    static final String KEY_ID = "ModSettingId";
    static final String KEY_BOOL = "@ModSettingBool";
    static final String KEY_VALUE = "@ModSettingValue";

    /** Same action key as the rest of TameworkSettingsPage's events. */
    private static final String KEY_ACTION = "Action";
    private static final String ROWS = "#TwModSettingsRows";
    private static final String MOD_HEADER_UI = "TameworkSettingsModHeader.ui";
    private static final String GROUP_HEADER_UI = "TameworkSettingsModGroupHeader.ui";
    private static final String ROW_UI = "TameworkSettingsModRow.ui";

    private final PlayerRef playerRef;
    private final Tamework plugin;
    /** Chosen values waiting for Apply: setting Id to the new value, or null to reset it to the default. */
    private final Map<String, JsonPrimitive> pendingValues = new LinkedHashMap<>();
    /** Text typed into number fields, waiting for Apply. */
    private final Map<String, String> pendingText = new LinkedHashMap<>();
    /** Row index of each rendered setting, for updating one row without rebuilding the list. */
    private final Map<String, Integer> rowIndexById = new HashMap<>();

    TameworkSettingsModTab(@Nonnull PlayerRef playerRef, @Nonnull Tamework plugin) {
        this.playerRef = Objects.requireNonNull(playerRef, "playerRef");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Edits collected for Apply. {@code invalidLabel} names a number field whose text is not a number;
     * the apply must stop then. The submitted maps let {@link #markApplied} keep edits made meanwhile.
     */
    record Collected(@Nonnull Map<String, JsonPrimitive> changes,
                     @Nullable String invalidLabel,
                     @Nonnull Map<String, JsonPrimitive> submittedValues,
                     @Nonnull Map<String, String> submittedText) {
    }

    boolean hasPendingEdits() {
        return !pendingValues.isEmpty() || !pendingText.isEmpty();
    }

    void discardPendingEdits() {
        pendingValues.clear();
        pendingText.clear();
    }

    /** Rebuilds every heading and row. Callers must also rebind the page's static events in {@code events}. */
    void render(@Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events) {
        commands.clear(ROWS);
        rowIndexById.clear();
        commands.set("#TwModSettingsScopeNote.Text", resolve("tamework.ui.settings.modSettings.scopeNote"));
        commands.set("#TwModSettingsEmptyLabel.Text", resolve("tamework.ui.settings.modSettings.empty"));
        ModSettingsService service = service();
        ModSettingsCatalog catalog = service == null ? ModSettingsCatalog.EMPTY : service.catalog();
        boolean empty = catalog.mods().isEmpty();
        commands.set("#TwModSettingsEmptyLabel.Visible", empty);
        commands.set("#TwModSettingsViewport.Visible", !empty);
        if (service == null) {
            return;
        }
        int index = 0;
        for (ModSettingsCatalog.ModSection mod : catalog.mods()) {
            commands.append(ROWS, MOD_HEADER_UI);
            commands.set(row(index++) + " #ModHeaderLabel.Text", mod.modName());
            for (ModSettingsCatalog.Group group : mod.groups()) {
                commands.append(ROWS, GROUP_HEADER_UI);
                commands.set(row(index++) + " #ModGroupLabel.Text", text(group.labelKey(), group.groupId()));
                for (TwModSetting setting : group.settings()) {
                    renderSetting(commands, events, service, setting, index++);
                }
            }
        }
    }

    /**
     * Records one control event and adds the row's updates to {@code commands}.
     * Returns false when the event does not name a current setting or carries no usable value.
     */
    boolean onEvent(@Nonnull String action,
                    @Nullable String id,
                    @Nullable Boolean bool,
                    @Nullable String value,
                    @Nonnull UICommandBuilder commands) {
        ModSettingsService service = service();
        TwModSetting setting = service == null || id == null ? null : service.catalog().setting(id);
        if (setting == null) {
            return false;
        }
        String key = setting.getId();
        switch (action) {
            case ACTION_BOOL -> {
                if (bool == null) {
                    return false;
                }
                pendingText.remove(key);
                pendingValues.put(key, new JsonPrimitive(bool));
            }
            case ACTION_CHOICE -> {
                JsonPrimitive choice = value == null ? null : setting.coerce(new JsonPrimitive(value));
                if (choice == null) {
                    return false;
                }
                pendingText.remove(key);
                pendingValues.put(key, choice);
            }
            case ACTION_TEXT -> {
                pendingValues.remove(key);
                pendingText.put(key, value == null ? "" : value);
            }
            case ACTION_RESET -> {
                pendingText.remove(key);
                pendingValues.put(key, null);
                Integer index = rowIndexById.get(key);
                if (index != null) {
                    setControlValue(commands, row(index), setting, setting.defaultValue());
                }
            }
            default -> {
                return false;
            }
        }
        dropUnchanged(service, setting);
        Integer index = rowIndexById.get(key);
        if (index != null) {
            commands.set(row(index) + " #ModSettingResetButton.Visible", resetVisible(service, setting));
        }
        return true;
    }

    /** The pending edits as {@link ModSettingsService#apply} changes. */
    @Nonnull
    Collected collect() {
        Map<String, JsonPrimitive> submittedValues = Collections.unmodifiableMap(new LinkedHashMap<>(pendingValues));
        Map<String, String> submittedText = Collections.unmodifiableMap(new LinkedHashMap<>(pendingText));
        Map<String, JsonPrimitive> changes = new LinkedHashMap<>(pendingValues);
        ModSettingsService service = service();
        for (Map.Entry<String, String> entry : pendingText.entrySet()) {
            TwModSetting setting = service == null ? null : service.catalog().setting(entry.getKey());
            if (setting == null) {
                continue;
            }
            JsonPrimitive parsed = setting.parse(entry.getValue());
            if (parsed == null) {
                return new Collected(Map.of(), text(setting.getLabel(), setting.getId()), submittedValues, submittedText);
            }
            changes.put(setting.getId(), parsed);
        }
        return new Collected(changes, null, submittedValues, submittedText);
    }

    /** Forgets the submitted edits once saved; edits changed after the submit stay pending. */
    void markApplied(@Nonnull Collected collected) {
        pendingValues.entrySet().removeIf(entry -> collected.submittedValues().containsKey(entry.getKey())
                && Objects.equals(collected.submittedValues().get(entry.getKey()), entry.getValue()));
        pendingText.entrySet().removeIf(entry -> collected.submittedText().containsKey(entry.getKey())
                && Objects.equals(collected.submittedText().get(entry.getKey()), entry.getValue()));
    }

    private void renderSetting(@Nonnull UICommandBuilder commands,
                               @Nonnull UIEventBuilder events,
                               @Nonnull ModSettingsService service,
                               @Nonnull TwModSetting setting,
                               int index) {
        String id = setting.getId();
        String root = row(index);
        rowIndexById.put(id, index);
        commands.append(ROWS, ROW_UI);
        commands.set(root + " #ModSettingLabel.Text", text(setting.getLabel(), id));
        String tooltip = tooltip(setting);
        commands.set(root + " #ModSettingTooltip.Visible", !tooltip.isEmpty());
        commands.set(root + " #ModSettingTooltip.TooltipText", tooltip);
        commands.set(root + " #ModSettingResetButton.Text", resolve("tamework.ui.shared.button.reset"));
        commands.set(root + " #ModSettingResetButton.TooltipText", resolve("tamework.ui.settings.modSettings.resetTooltip"));
        commands.set(root + " #ModSettingResetButton.Visible", resetVisible(service, setting));

        TwModSettingType type = setting.getType();
        boolean number = type == TwModSettingType.INTEGER || type == TwModSettingType.DECIMAL;
        commands.set(root + " #ModSettingCheck.Visible", type == TwModSettingType.BOOLEAN);
        commands.set(root + " #ModSettingDropdown.Visible", type == TwModSettingType.ENUM);
        commands.set(root + " #ModSettingInput.Visible", number);
        switch (type) {
            case BOOLEAN -> {
                setControlValue(commands, root, setting, shownValue(service, setting));
                bindValueChanged(events, root + " #ModSettingCheck", ACTION_BOOL, id, KEY_BOOL);
            }
            case ENUM -> {
                commands.set(root + " #ModSettingDropdown.NoItemsText", resolve("tamework.ui.settings.modSettings.noOptions"));
                commands.set(root + " #ModSettingDropdown.Entries", optionEntries(setting));
                setControlValue(commands, root, setting, shownValue(service, setting));
                bindValueChanged(events, root + " #ModSettingDropdown", ACTION_CHOICE, id, KEY_VALUE);
            }
            case INTEGER, DECIMAL -> {
                String typed = pendingText.get(id);
                if (typed != null) {
                    commands.set(root + " #ModSettingInput.Value", typed);
                } else {
                    setControlValue(commands, root, setting, shownValue(service, setting));
                }
                bindValueChanged(events, root + " #ModSettingInput", ACTION_TEXT, id, KEY_VALUE);
            }
        }
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                root + " #ModSettingResetButton",
                EventData.of(KEY_ACTION, ACTION_RESET).append(KEY_ID, id),
                false
        );
    }

    private static void bindValueChanged(@Nonnull UIEventBuilder events,
                                         @Nonnull String selector,
                                         @Nonnull String action,
                                         @Nonnull String id,
                                         @Nonnull String valueKey) {
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                selector,
                EventData.of(KEY_ACTION, action).append(KEY_ID, id).append(valueKey, selector + ".Value"),
                false
        );
    }

    private static void setControlValue(@Nonnull UICommandBuilder commands,
                                        @Nonnull String root,
                                        @Nonnull TwModSetting setting,
                                        @Nullable JsonPrimitive value) {
        switch (setting.getType()) {
            case BOOLEAN -> commands.set(root + " #ModSettingCheck.Value", value != null && value.isBoolean() && value.getAsBoolean());
            case ENUM -> commands.set(root + " #ModSettingDropdown.Value", value == null ? "" : value.getAsString());
            case INTEGER, DECIMAL -> commands.set(root + " #ModSettingInput.Value", value == null ? "" : value.getAsString());
        }
    }

    /** The value the row stands for: pending edit, else saved value, else default; null for unparsable text. */
    @Nullable
    private JsonPrimitive shownValue(@Nonnull ModSettingsService service, @Nonnull TwModSetting setting) {
        String id = setting.getId();
        if (pendingText.containsKey(id)) {
            return setting.parse(pendingText.get(id));
        }
        if (pendingValues.containsKey(id)) {
            JsonPrimitive pending = pendingValues.get(id);
            return pending == null ? setting.defaultValue() : pending;
        }
        JsonPrimitive saved = service.value(id);
        return saved == null ? setting.defaultValue() : saved;
    }

    /** Reset shows when the row differs from the default, or when a saved value equal to the default would be removed. */
    private boolean resetVisible(@Nonnull ModSettingsService service, @Nonnull TwModSetting setting) {
        String id = setting.getId();
        boolean pendingValue = pendingValues.containsKey(id);
        if (pendingValue && pendingValues.get(id) == null) {
            return false;
        }
        JsonPrimitive shown = shownValue(service, setting);
        if (shown == null || !shown.equals(setting.defaultValue())) {
            return true;
        }
        return service.isCustomized(id) && !pendingValue && !pendingText.containsKey(id);
    }

    /** An edit back to what is already saved is not a change. */
    private void dropUnchanged(@Nonnull ModSettingsService service, @Nonnull TwModSetting setting) {
        String id = setting.getId();
        JsonPrimitive saved = service.value(id);
        if (pendingValues.containsKey(id)) {
            JsonPrimitive pending = pendingValues.get(id);
            if (pending == null ? !service.isCustomized(id) : pending.equals(saved)) {
                pendingValues.remove(id);
            }
        }
        if (pendingText.containsKey(id)) {
            JsonPrimitive parsed = setting.parse(pendingText.get(id));
            if (parsed != null && parsed.equals(saved)) {
                pendingText.remove(id);
            }
        }
    }

    @Nonnull
    private List<DropdownEntryInfo> optionEntries(@Nonnull TwModSetting setting) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        if (setting instanceof TwModSetting.EnumSetting enumSetting) {
            for (TwModSetting.EnumOption option : enumSetting.getOptions()) {
                if (option == null || option.getValue() == null) {
                    continue;
                }
                entries.add(new DropdownEntryInfo(
                        LocalizableString.fromString(text(option.getLabel(), option.getValue())),
                        option.getValue()));
            }
        }
        return entries;
    }

    /** Description, then the allowed range for numbers, one per line. */
    @Nonnull
    private String tooltip(@Nonnull TwModSetting setting) {
        String description = setting.getDescription() == null ? "" : text(setting.getDescription(), "");
        String range = rangeText(setting);
        if (description.isEmpty()) {
            return range;
        }
        return range.isEmpty() ? description : description + "\n" + range;
    }

    @Nonnull
    private String rangeText(@Nonnull TwModSetting setting) {
        String min = null;
        String max = null;
        if (setting instanceof TwModSetting.IntegerSetting number) {
            min = number.getMin() == null ? null : String.valueOf(number.getMin());
            max = number.getMax() == null ? null : String.valueOf(number.getMax());
        } else if (setting instanceof TwModSetting.DecimalSetting number) {
            min = plain(number.getMin());
            max = plain(number.getMax());
        }
        if (min != null && max != null) {
            return LocalizedText.format(playerRef, "tamework.ui.settings.modSettings.range.between", min, max);
        }
        if (min != null) {
            return LocalizedText.format(playerRef, "tamework.ui.settings.modSettings.range.min", min);
        }
        if (max != null) {
            return LocalizedText.format(playerRef, "tamework.ui.settings.modSettings.range.max", max);
        }
        return "";
    }

    @Nullable
    private static String plain(@Nullable Double value) {
        return value == null || !Double.isFinite(value) ? null : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** A mod's language key resolved for this viewer, or {@code fallback} when there is no key. */
    @Nonnull
    private String text(@Nullable String key, @Nonnull String fallback) {
        return LocalizedText.resolveConfigValue(playerRef.getLanguage(), key, fallback);
    }

    @Nonnull
    private String resolve(@Nonnull String key) {
        return LocalizedText.resolve(playerRef, key);
    }

    @Nullable
    private ModSettingsService service() {
        return plugin.getModSettingsService();
    }

    @Nonnull
    private static String row(int index) {
        return ROWS + "[" + index + "]";
    }
}
