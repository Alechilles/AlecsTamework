package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Saved views change presentation; the flute's command selection remains separate. */
final class CompanionViewControls {
    private static final String PREFIX = "__view__:";
    private enum Editor { NONE, FILTER, NAME, ICON }

    private final TameworkCommandSelectionPage page;
    private Editor editor = Editor.NONE;
    private String filterType = "choose";
    private List<String> species = List.of();
    private List<String> groups = List.of();

    CompanionViewControls(TameworkCommandSelectionPage page) { this.page = page; }

    void bind(UICommandBuilder c, UIEventBuilder e, LinkedNpcPanelRefreshValues values) {
        var binding = page.viewBinding;
        var current = binding.current().get();
        String language = page.resolveLanguage();
        String id = binding.selectedId().get();
        var saved = binding.views().get();
        var selectedView = saved.stream().filter(v -> v.id().equals(id)).findFirst();
        boolean modified = selectedView.isPresent() && !selectedView.get().settings().equals(current);
        boolean customDraft = id.isBlank()
                || "__all__".equals(id) && !current.equals(CompanionViewSettings.defaults().withState("All"))
                || "__selected__".equals(id) && !current.equals(new CompanionViewSettings("All", false, "", "Default", true, List.of(), List.of()));
        boolean unsaved = modified || customDraft && (!id.isBlank() || !current.equals(CompanionViewSettings.defaults()));
        var entries = new ArrayList<DropdownEntryInfo>();
        entries.add(option(text("all"), "__all__"));
        entries.add(option(text("selected"), "__selected__"));
        for (var view : saved) entries.add(option(view.name(), view.id()));
        if (customDraft) entries.add(option(text("custom"), "__custom__"));
        set(c, values, "#CompanionViewPicker.Entries", entries);
        set(c, values, "#CompanionViewPicker.Value", customDraft ? "__custom__" : id);
        set(c, values, "#CompanionViewSaveNew.TooltipText", text(unsaved ? "modifiedHint" : "saveButton"));
        set(c, values, "#CompanionViewSaveNew.OutlineColor", unsaved ? "#f1c66a" : "#4a5a50");
        set(c, values, "#CompanionViewSaveNew.OutlineSize", 1);
        set(c, values, "#CompanionViewSaveGlyph.Visible", !unsaved);
        set(c, values, "#CompanionViewSaveGlyphUnsaved.Visible", unsaved);
        boolean hasIconOptions = !binding.iconOptions().get().isEmpty();
        set(c, values, "#CompanionViewIconButton.Visible", hasIconOptions);
        set(c, values, "#CompanionViewIconGlyph.Visible", hasIconOptions);

        boolean extra = current.hasExtraFilters();
        set(c, values, "#CompanionFilterChips.Visible", extra);
        if (values == null || values.changed("viewContentTop", extra)) {
            Anchor content = new Anchor();
            content.setLeft(Value.of(10)); content.setRight(Value.of(10));
            content.setTop(Value.of(extra ? 114 : 80)); content.setBottom(Value.of(10));
            c.setObject("#TameworkLinkedPanelRoot #Content.Anchor", content);
        }
        int left = 0;
        left = chip(c, values, "Groups", current.groupIds(), groupNames(), left, "groupChip");
        left = chip(c, values, "Species", current.speciesIds(), speciesNames(), left, "speciesChip");
        set(c, values, "#CompanionChipSelected.Visible", current.selectedOnly());
        if (current.selectedOnly() && (values == null || values.changed("#CompanionChipSelected.left", left)))
            c.setObject("#CompanionChipSelected.Anchor", anchor(left, 0, 156, 28));
        var roster = page.rosterSummaries();
        long selectedTotal = Arrays.stream(roster).filter(LinkedNpcEntry::active).count();
        long matchingSelected = Arrays.stream(current.filter(roster)).filter(LinkedNpcEntry::active).count();
        set(c, values, "#CompanionHiddenSelection.Text", LocalizedText.format(language,
                "tamework.ui.views.outside", Math.max(0, selectedTotal - matchingSelected)));
        set(c, values, "#CompanionSelectMatching.Visible", true);
        set(c, values, "#CompanionSelectMatching.Disabled", Arrays.stream(current.filter(roster)).noneMatch(LinkedNpcEntry::selectionSupported));
        if (values != null) return;

        c.setObject("#TameworkLinkedPanelControlsSecondary.Anchor", anchor(280, 36, 520, 28));
        c.setObject("#TameworkLinkedPanelInlineFilterTextControls.Anchor", anchor(0, 0, 340, 28));
        c.setObject("#TameworkLinkedPanelFilterInput.Anchor", anchor(0, 0, 330, 28));
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewPicker",
                EventData.of("@ViewId", "#CompanionViewPicker.Value"), false);
        bindButton(e, "#CompanionViewSaveNew", "saveView");
        bindButton(e, "#CompanionViewManage", "manage");
        bindButton(e, "#CompanionViewIconButton", "icon");
        bindButton(e, "#CompanionAddFilter", "addFilter");
        for (String type : List.of("Groups", "Species", "Selected")) {
            bindButton(e, "#CompanionChip" + type + "Edit", "edit" + type);
            bindButton(e, "#CompanionChip" + type + "Remove", "clear" + type);
        }
        bindButton(e, "#CompanionSelectMatching", "select");
        bindButton(e, "#CompanionFilterCancel", "cancel");
        bindButton(e, "#CompanionViewCancel", "cancel");
        bindButton(e, "#CompanionViewIconCancel", "cancel");
        bindButton(e, "#CompanionViewFilterSave", "filterSave");
        bindButton(e, "#CompanionViewDelete", "delete");
        e.addEventBinding(CustomUIEventBindingType.Activating, "#CompanionViewSave",
                EventData.of("CommandId", PREFIX + "save").append("@ViewName", "#CompanionViewName.Value"), false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewType",
                EventData.of("@ViewType", "#CompanionViewType.Value"), false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewSpecies",
                EventData.of("@ViewSpecies", "#CompanionViewSpecies.SelectedValues"), false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewGroups",
                EventData.of("@ViewGroups", "#CompanionViewGroups.SelectedValues"), false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewIconPicker",
                EventData.of("@ViewIconItemId", "#CompanionViewIconPicker.Value"), false);
    }

    /** A visible editor rejects queued card and assignment events from the covered page. */
    boolean blocksWhileEditing(CommandSelectionEventData data) {
        if (editor == Editor.NONE) return false;
        String command = data.commandId == null ? "" : data.commandId;
        if (data.primaryCommandValue != null || data.hotswapQValue != null || data.hotswapEValue != null
                || data.hotswapRValue != null || data.viewId != null) return true;
        if (CommandSelectionPageEventBinder.CLOSE_COMMAND_ID.equals(command)) return false;
        if (command.equals(PREFIX + "cancel") || command.equals(PREFIX + "filterSave")
                || command.equals(PREFIX + "save") || command.equals(PREFIX + "delete")) return false;
        return data.viewType == null && data.viewSpecies == null && data.viewGroups == null
                && (editor != Editor.ICON || data.viewIconItemId == null);
    }

    boolean handle(CommandSelectionEventData data, String command) {
        var b = page.viewBinding;
        if (data.viewId != null) {
            if (Objects.equals(data.viewId, b.selectedId().get())) return true;
            page.flushViewSearch();
            b.choose().accept(data.viewId);
            page.refreshView();
            return true;
        }
        if (data.viewIconItemId != null) {
            if (editor != Editor.ICON) return true;
            var chosen = b.iconOptions().get().stream()
                    .filter(option -> Objects.equals(option.itemId(), data.viewIconItemId))
                    .findFirst().orElse(null);
            if (chosen == null) return true;
            if (!Objects.equals(chosen.itemId(), b.selectedIconItemId().get())) {
                b.chooseIcon().accept(chosen.itemId());
            }
            UICommandBuilder c = new UICommandBuilder();
            preview(c, chosen);
            page.packetSender.send(c, new UIEventBuilder());
            return true;
        }
        if (data.viewType != null || data.viewSpecies != null || data.viewGroups != null) {
            if (editor != Editor.FILTER) return true;
            if (data.viewType != null && List.of("choose", "species", "groups", "selected").contains(data.viewType)) {
                filterType = data.viewType;
                showFilterType();
            }
            if (data.viewSpecies != null && data.viewSpecies.length <= 128) {
                species = validChoices(data.viewSpecies, speciesNames(), species);
                updateCaption("Species", species, speciesNames(), "anySpecies");
            }
            if (data.viewGroups != null && data.viewGroups.length <= 128) {
                groups = validChoices(data.viewGroups, groupNames(), groups);
                updateCaption("Groups", groups, groupNames(), "anyGroup");
            }
            return true;
        }
        if (command.startsWith(PREFIX)) {
            page.flushViewSearch();
            var current = b.current().get();
            String action = command.substring(PREFIX.length());
            switch (action) {
                case "addFilter" -> { openFilter("choose"); return true; }
                case "editGroups" -> { openFilter("groups"); return true; }
                case "editSpecies" -> { openFilter("species"); return true; }
                case "editSelected" -> { openFilter("selected"); return true; }
                case "manage" -> { openName(); return true; }
                case "icon" -> { if (!b.iconOptions().get().isEmpty()) openIcon(); return true; }
                case "saveView" -> {
                    if (selectedView() == null) { openName(); return true; }
                    b.save().accept("", true);
                }
                case "clearGroups" -> b.edit().accept(current.withExtraFilters(current.selectedOnly(), current.speciesIds(), List.of()));
                case "clearSpecies" -> b.edit().accept(current.withExtraFilters(current.selectedOnly(), List.of(), current.groupIds()));
                case "clearSelected" -> b.edit().accept(current.withExtraFilters(false, current.speciesIds(), current.groupIds()));
                case "select" -> b.selectMatching().run();
                case "cancel" -> { close(); return true; }
                case "filterSave" -> {
                    if (editor != Editor.FILTER) return true;
                    switch (filterType) {
                        case "species" -> b.edit().accept(current.withExtraFilters(current.selectedOnly(), species, current.groupIds()));
                        case "groups" -> b.edit().accept(current.withExtraFilters(current.selectedOnly(), current.speciesIds(), groups));
                        case "selected" -> b.edit().accept(current.withExtraFilters(true, current.speciesIds(), current.groupIds()));
                        default -> { error("chooseFilterType"); return true; }
                    }
                    close();
                }
                case "save" -> {
                    if (editor != Editor.NAME) return true;
                    String name = data.viewName == null ? "" : data.viewName.strip();
                    if (name.isEmpty()) { error("nameRequired"); return true; }
                    var saved = selectedView();
                    if (saved == null && b.views().get().size() >= 16) { error("limit"); return true; }
                    if (saved == null) b.save().accept(name, false);
                    else {
                        b.save().accept("", true);
                        if (!saved.name().equals(name)) b.rename().accept(name);
                    }
                    close();
                }
                case "delete" -> {
                    if (editor != Editor.NAME || selectedView() == null) return true;
                    b.delete().run();
                    close();
                }
                default -> { return true; }
            }
            page.refreshView();
            return true;
        }
        if (command.startsWith(CompanionPanelChrome.FILTER_PREFIX)) {
            String state = command.substring(CompanionPanelChrome.FILTER_PREFIX.length());
            if (CompanionPanelChrome.FILTERS.contains(state)) {
                page.flushViewSearch(); b.edit().accept(b.current().get().withState(state)); page.refreshView();
            }
            return true;
        }
        if (data.companionNearby != null || data.panelSortValue != null) {
            page.flushViewSearch();
            var current = b.current().get();
            b.edit().accept(data.companionNearby != null ? current.withNearby(data.companionNearby) : current.withSort(data.panelSortValue));
            page.refreshView(); return true;
        }
        return false;
    }

    private CompanionViewBinding.View selectedView() {
        String id = page.viewBinding.selectedId().get();
        return page.viewBinding.views().get().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    }

    private void openFilter(String type) {
        editor = Editor.FILTER;
        filterType = type;
        var current = page.viewBinding.current().get();
        species = current.speciesIds(); groups = current.groupIds();
        UICommandBuilder c = new UICommandBuilder();
        c.set("#CompanionViewOverlay.Visible", true);
        c.set("#CompanionFilterEditor.Visible", true);
        c.set("#CompanionViewNameEditor.Visible", false);
        c.set("#CompanionViewIconEditor.Visible", false);
        c.set("#CompanionViewError.Text", "");
        c.set("#CompanionViewType.Entries", List.of(
                option(text("chooseFilterType"), "choose"), option(text("species"), "species"),
                option(text("groups"), "groups"), option(text("selectedOnly"), "selected")));
        c.set("#CompanionViewType.Value", type);
        populate(c, "#CompanionViewSpecies", speciesNames(), species);
        populate(c, "#CompanionViewGroups", groupNames(), groups);
        caption(c, "Species", species, speciesNames(), "anySpecies");
        caption(c, "Groups", groups, groupNames(), "anyGroup");
        filterRows(c);
        page.packetSender.send(c, new UIEventBuilder());
    }

    private void openName() {
        editor = Editor.NAME;
        var saved = selectedView();
        UICommandBuilder c = new UICommandBuilder();
        c.set("#CompanionViewOverlay.Visible", true);
        c.set("#CompanionFilterEditor.Visible", false);
        c.set("#CompanionViewNameEditor.Visible", true);
        c.set("#CompanionViewIconEditor.Visible", false);
        c.set("#CompanionViewNameError.Text", "");
        c.set("#CompanionViewName.Value", saved == null ? "" : saved.name());
        c.set("#CompanionViewDelete.Visible", saved != null);
        page.packetSender.send(c, new UIEventBuilder());
    }

    private void openIcon() {
        var binding = page.viewBinding;
        List<CompanionViewBinding.IconOption> options = binding.iconOptions().get();
        if (options.isEmpty()) return;
        editor = Editor.ICON;
        UICommandBuilder c = new UICommandBuilder();
        c.set("#CompanionViewOverlay.Visible", true);
        c.set("#CompanionFilterEditor.Visible", false);
        c.set("#CompanionViewNameEditor.Visible", false);
        c.set("#CompanionViewIconEditor.Visible", true);
        c.set("#CompanionViewIconPicker.Entries", options.stream()
                .map(entry -> option(entry.label(), entry.itemId())).toList());
        String selectedId = binding.selectedIconItemId().get();
        var selected = options.stream().filter(option -> Objects.equals(option.itemId(), selectedId))
                .findFirst().orElse(options.getFirst());
        c.set("#CompanionViewIconPicker.Value", selected.itemId());
        preview(c, selected);
        page.packetSender.send(c, new UIEventBuilder());
    }

    private static void preview(UICommandBuilder c, CompanionViewBinding.IconOption selected) {
        c.set("#CompanionViewIconPreview.ItemId", selected.itemId());
    }

    private void showFilterType() {
        UICommandBuilder c = new UICommandBuilder();
        filterRows(c);
        c.set("#CompanionViewError.Text", "");
        page.packetSender.send(c, new UIEventBuilder());
    }

    private void filterRows(UICommandBuilder c) {
        c.set("#CompanionViewSpeciesRow.Visible", "species".equals(filterType));
        c.set("#CompanionViewGroupsRow.Visible", "groups".equals(filterType));
        c.set("#CompanionViewSelectedHint.Visible", "selected".equals(filterType));
    }

    private void populate(UICommandBuilder c, String selector, Map<String, String> names, List<String> chosen) {
        var options = new LinkedHashMap<>(names);
        for (String id : chosen) options.putIfAbsent(id, LocalizedText.format(page.resolveLanguage(), "tamework.ui.views.unavailable", id));
        c.set(selector + ".Entries", options.entrySet().stream().map(v -> option(v.getValue(), v.getKey())).toList());
        c.set(selector + ".SelectedValues", chosen.stream().map(LocalizableString::fromString).toList());
    }

    private void updateCaption(String suffix, List<String> ids, Map<String, String> names, String emptyKey) {
        UICommandBuilder c = new UICommandBuilder();
        caption(c, suffix, ids, names, emptyKey);
        page.packetSender.send(c, new UIEventBuilder());
    }

    private void caption(UICommandBuilder c, String suffix, List<String> ids, Map<String, String> names, String emptyKey) {
        String label = ids.isEmpty() ? text(emptyKey) : String.join(", ", ids.stream().map(id -> names.getOrDefault(id, id)).toList());
        c.set("#CompanionView" + suffix + "Label.Text", label.length() > 42 ? label.substring(0, 41) + "…" : label);
    }

    private void close() {
        editor = Editor.NONE;
        UICommandBuilder c = new UICommandBuilder();
        c.set("#CompanionViewOverlay.Visible", false);
        page.packetSender.send(c, new UIEventBuilder());
    }

    private void error(String key) {
        UICommandBuilder c = new UICommandBuilder();
        c.set(editor == Editor.NAME ? "#CompanionViewNameError.Text" : "#CompanionViewError.Text", text(key));
        page.packetSender.send(c, new UIEventBuilder());
    }

    private Map<String, String> speciesNames() {
        var names = new TreeMap<String, String>();
        for (var entry : page.rosterSummaries()) if (entry.speciesId() != null && !entry.speciesId().isBlank())
            names.put(entry.speciesId(), entry.speciesLabel() == null || entry.speciesLabel().isBlank() ? entry.speciesId() : entry.speciesLabel());
        return names;
    }

    private Map<String, String> groupNames() {
        var names = new LinkedHashMap<String, String>();
        for (var entry : page.rosterSummaries()) for (var group : entry.groups()) names.put(group.id(), group.name());
        return names;
    }

    private int chip(UICommandBuilder c, LinkedNpcPanelRefreshValues values, String suffix, List<String> ids,
                     Map<String, String> names, int left, String key) {
        String selector = "#CompanionChip" + suffix;
        set(c, values, selector + ".Visible", !ids.isEmpty());
        if (ids.isEmpty()) return left;
        String label = String.join(", ", ids.stream().map(id -> names.getOrDefault(id, id)).toList());
        String full = LocalizedText.format(page.resolveLanguage(), "tamework.ui.views." + key, label);
        set(c, values, selector + "Edit.TooltipText", full);
        if (label.length() > 18) label = label.substring(0, 17) + "…";
        String shown = LocalizedText.format(page.resolveLanguage(), "tamework.ui.views." + key, label);
        set(c, values, selector + "Edit.Text", shown);
        int width = Math.clamp(shown.length() * 7 + 56, 132, 224);
        if (values == null || values.changed(selector + ".layout", left + ":" + width))
            c.setObject(selector + ".Anchor", anchor(left, 0, width, 28));
        return left + width + 6;
    }

    private static List<String> validChoices(String[] values, Map<String, String> available, List<String> existing) {
        return Arrays.stream(values).filter(Objects::nonNull).filter(v -> available.containsKey(v) || existing.contains(v)).distinct().toList();
    }
    private static DropdownEntryInfo option(String label, String id) { return new DropdownEntryInfo(LocalizableString.fromString(label), id); }
    private String text(String key) { return LocalizedText.resolve(page.resolveLanguage(), "tamework.ui.views." + key); }
    private static void bindButton(UIEventBuilder e, String selector, String action) {
        e.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("CommandId", PREFIX + action), false);
    }
    private static Anchor anchor(int left, int top, int width, int height) {
        var a = new Anchor(); a.setLeft(Value.of(left)); a.setTop(Value.of(top)); a.setWidth(Value.of(width)); a.setHeight(Value.of(height)); return a;
    }
    private static void set(UICommandBuilder c, LinkedNpcPanelRefreshValues values, String selector, Object value) {
        if (values != null && !values.changed(selector, value)) return;
        if (value instanceof String s) c.set(selector, s);
        else if (value instanceof Boolean b) c.set(selector, b);
        else if (value instanceof List<?> list) c.set(selector, list);
    }
}
