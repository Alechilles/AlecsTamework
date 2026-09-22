package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.*;

/** Compact view chrome and page-local editor. Presets change presentation only. */
final class CompanionViewControls {
    private static final String PREFIX = "__view__:";
    private final TameworkCommandSelectionPage page;
    private boolean editing;
    private List<String> species = List.of();
    private List<String> groups = List.of();
    private boolean selected;

    CompanionViewControls(TameworkCommandSelectionPage page) { this.page = page; }

    void bind(UICommandBuilder c, UIEventBuilder e, LinkedNpcPanelRefreshValues values) {
        var binding = page.viewBinding;
        var current = binding.current().get();
        String language = page.resolveLanguage();
        var entries = new ArrayList<DropdownEntryInfo>();
        entries.add(option(text("all"), "__all__"));
        entries.add(option(text("selected"), "__selected__"));
        String id = binding.selectedId().get();
        var saved = binding.views().get();
        boolean modified = saved.stream().filter(v -> v.id().equals(id))
                .anyMatch(v -> !v.settings().equals(current));
        for (var view : saved) entries.add(option(view.name() + (modified && view.id().equals(id) ? " *" : ""), view.id()));
        boolean customDraft = id.isBlank()
                || "__all__".equals(id) && !current.equals(CompanionViewSettings.defaults().withState("All"))
                || "__selected__".equals(id) && !current.equals(new CompanionViewSettings("All", false, "", "Default", true, List.of(), List.of()));
        if (customDraft) entries.add(option(text("custom"), "__custom__"));
        set(c, values, "#CompanionViewPicker.Entries", entries);
        set(c, values, "#CompanionViewPicker.Value", customDraft ? "__custom__" : id);
        set(c, values, "#CompanionViewToolbar.TooltipText", text(modified || customDraft ? "modifiedHint" : "openHint"));
        boolean extra = current.hasExtraFilters();
        set(c, values, "#CompanionFilterChips.Visible", extra);
        // Both anchors move together; the footer remains inside Content's bottom edge.
        if (values == null || values.changed("viewContentTop", extra)) {
            Anchor content = new Anchor();
            content.setLeft(Value.of(10)); content.setRight(Value.of(10));
            content.setTop(Value.of(extra ? 114 : 80)); content.setBottom(Value.of(10));
            c.setObject("#TameworkLinkedPanelRoot #Content.Anchor", content);
        }
        int left = 0;
        left = chip(c, values, "Groups", current.groupIds(), groupNames(), left, 175, "groupChip");
        left = chip(c, values, "Species", current.speciesIds(), speciesNames(), left, 195, "speciesChip");
        set(c, values, "#CompanionChipSelected.Visible", current.selectedOnly());
        if (current.selectedOnly()) c.setObject("#CompanionChipSelected.Anchor", anchor(left, 0, 135, 26));
        var roster = page.rosterSummaries();
        long selectedTotal = Arrays.stream(roster).filter(LinkedNpcEntry::active).count();
        long matchingSelected = Arrays.stream(current.filter(roster)).filter(LinkedNpcEntry::active).count();
        set(c, values, "#CompanionHiddenSelection.Text", LocalizedText.format(language,
                "tamework.ui.views.outside", Math.max(0, selectedTotal - matchingSelected)));
        set(c, values, "#CompanionSelectMatching.Visible", true);
        set(c, values, "#CompanionSelectMatching.Disabled", Arrays.stream(current.filter(roster)).noneMatch(LinkedNpcEntry::selectionSupported));
        if (values != null) return;
        // Reclaim the original two toolbar rows. Saved views share the status-tab row.
        c.setObject("#TameworkLinkedPanelControlsSecondary.Anchor", anchor(280, 36, 520, 28));
        c.setObject("#TameworkLinkedPanelInlineFilterTextControls.Anchor", anchor(0, 0, 340, 28));
        c.setObject("#TameworkLinkedPanelFilterInput.Anchor", anchor(0, 0, 330, 28));
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewPicker",
                EventData.of("@ViewId", "#CompanionViewPicker.Value"), false);
        bindButton(e, "#CompanionViewSaveNew", "open");
        bindButton(e, "#CompanionViewManage", "open");
        bindButton(e, "#CompanionAddFilter", "open");
        bindButton(e, "#CompanionChipGroups", "clearGroups");
        bindButton(e, "#CompanionChipSpecies", "clearSpecies");
        bindButton(e, "#CompanionChipSelected", "clearSelected");
        bindButton(e, "#CompanionSelectMatching", "select");
        for (String action : List.of("apply", "save", "update", "rename", "delete", "cancel")) {
            e.addEventBinding(CustomUIEventBindingType.Activating, "#CompanionView" + capitalize(action),
                    EventData.of("CommandId", PREFIX + action).append("@ViewName", "#CompanionViewName.Value"), false);
        }
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewSpecies",
                EventData.of("@ViewSpecies", "#CompanionViewSpecies.SelectedValues"), false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewGroups",
                EventData.of("@ViewGroups", "#CompanionViewGroups.SelectedValues"), false);
        e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionViewSelected",
                EventData.of("@ViewSelected", "#CompanionViewSelected.Value"), false);
    }

    /** A visible editor rejects queued card/assignment events from the covered page. */
    boolean blocksWhileEditing(CommandSelectionEventData data) {
        if (!editing) return false;
        String command = data.commandId == null ? "" : data.commandId;
        if (data.primaryCommandValue != null || data.hotswapQValue != null || data.hotswapEValue != null
                || data.hotswapRValue != null || data.viewId != null) return true;
        if (CommandSelectionPageEventBinder.CLOSE_COMMAND_ID.equals(command)) return false;
        if (List.of("apply", "save", "update", "rename", "delete", "cancel")
                .stream().anyMatch(action -> (PREFIX + action).equals(command))) return false;
        return !command.isBlank() || data.viewSpecies == null && data.viewGroups == null && data.viewSelected == null;
    }

    boolean handle(CommandSelectionEventData data, String command) {
        var b = page.viewBinding;
        if (data.viewId != null) {
            page.flushViewSearch();
            b.choose().accept(data.viewId);
            page.refreshView();
            return true;
        }
        if (data.viewSpecies != null || data.viewGroups != null || data.viewSelected != null) {
            if (!editing) return true;
            if (data.viewSpecies != null && data.viewSpecies.length <= 128)
                species = validChoices(data.viewSpecies, speciesNames(), species);
            if (data.viewGroups != null && data.viewGroups.length <= 128)
                groups = validChoices(data.viewGroups, groupNames(), groups);
            if (data.viewSelected != null) selected = data.viewSelected;
            return true;
        }
        if (command.startsWith(PREFIX)) {
            String action = command.substring(PREFIX.length());
            page.flushViewSearch();
            var current = b.current().get();
            switch (action) {
                case "open" -> { open(); return true; }
                case "clearGroups" -> b.edit().accept(new CompanionViewSettings(current.state(), current.nearby(), current.search(), current.sort(), current.selectedOnly(), current.speciesIds(), List.of()));
                case "clearSpecies" -> b.edit().accept(new CompanionViewSettings(current.state(), current.nearby(), current.search(), current.sort(), current.selectedOnly(), List.of(), current.groupIds()));
                case "clearSelected" -> b.edit().accept(new CompanionViewSettings(current.state(), current.nearby(), current.search(), current.sort(), false, current.speciesIds(), current.groupIds()));
                case "select" -> b.selectMatching().run();
                case "cancel" -> { close(); return true; }
                case "apply", "save", "update", "rename", "delete" -> {
                    if (!editing) return true;
                    String name = data.viewName == null ? "" : data.viewName.strip();
                    if ((action.equals("save") || action.equals("rename")) && name.isEmpty()) {
                        error("nameRequired"); return true;
                    }
                    if (action.equals("save") && b.views().get().size() >= 16) {
                        error("limit"); return true;
                    }
                    if (action.equals("apply") || action.equals("save") || action.equals("update")) {
                        b.edit().accept(new CompanionViewSettings(current.state(), current.nearby(), current.search(),
                                current.sort(), selected, species, groups));
                    }
                    if (action.equals("save")) b.save().accept(name, false);
                    if (action.equals("update")) b.save().accept(name, true);
                    if (action.equals("rename")) b.rename().accept(name);
                    if (action.equals("delete")) b.delete().run();
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

    private void open() {
        editing = true;
        var b = page.viewBinding;
        var current = b.current().get();
        species = current.speciesIds(); groups = current.groupIds(); selected = current.selectedOnly();
        UICommandBuilder c = new UICommandBuilder();
        c.set("#CompanionViewOverlay.Visible", true);
        c.set("#CompanionViewError.Text", "");
        var saved = b.views().get().stream().filter(v -> v.id().equals(b.selectedId().get())).findFirst();
        c.set("#CompanionViewName.Value", saved.map(CompanionViewBinding.View::name).orElse(""));
        c.set("#CompanionViewUpdate.Disabled", saved.isEmpty());
        c.set("#CompanionViewRename.Disabled", saved.isEmpty());
        c.set("#CompanionViewDelete.Disabled", saved.isEmpty());
        populate(c, "#CompanionViewSpecies", speciesNames(), species);
        populate(c, "#CompanionViewGroups", groupNames(), groups);
        c.set("#CompanionViewSelected.Value", selected);
        page.packetSender.send(c, new UIEventBuilder());
    }

    private void populate(UICommandBuilder c, String selector, Map<String, String> names, List<String> chosen) {
        var options = new LinkedHashMap<>(names);
        for (String id : chosen) options.putIfAbsent(id, LocalizedText.format(page.resolveLanguage(), "tamework.ui.views.unavailable", id));
        c.set(selector + ".Entries", options.entrySet().stream().map(v -> option(v.getValue(), v.getKey())).toList());
        c.set(selector + ".SelectedValues", chosen.stream().map(LocalizableString::fromString).toList());
    }
    private void close() {
        editing = false;
        UICommandBuilder c = new UICommandBuilder(); c.set("#CompanionViewOverlay.Visible", false);
        page.packetSender.send(c, new UIEventBuilder());
    }
    private void error(String key) {
        UICommandBuilder c = new UICommandBuilder(); c.set("#CompanionViewError.Text", text(key));
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
                     Map<String, String> names, int left, int width, String key) {
        String selector = "#CompanionChip" + suffix;
        set(c, values, selector + ".Visible", !ids.isEmpty());
        if (ids.isEmpty()) return left;
        String label = String.join(", ", ids.stream().map(id -> names.getOrDefault(id, id)).toList());
        String full = LocalizedText.format(page.resolveLanguage(), "tamework.ui.views." + key, label);
        set(c, values, selector + ".TooltipText", full);
        if (label.length() > 18) label = label.substring(0, 17) + "...";
        set(c, values, selector + ".Text", LocalizedText.format(page.resolveLanguage(), "tamework.ui.views." + key, label));
        if (values == null || values.changed(selector + ".left", left)) c.setObject(selector + ".Anchor", anchor(left, 0, width, 26));
        return left + width + 6;
    }
    private static List<String> validChoices(String[] values, Map<String, String> available, List<String> existing) {
        return Arrays.stream(values).filter(Objects::nonNull).filter(v -> available.containsKey(v) || existing.contains(v)).distinct().toList();
    }
    private static DropdownEntryInfo option(String label, String id) { return new DropdownEntryInfo(LocalizableString.fromString(label), id); }
    private String text(String key) { return LocalizedText.resolve(page.resolveLanguage(), "tamework.ui.views." + key); }
    private static String capitalize(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
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
