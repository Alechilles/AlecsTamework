package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/** Persists a flute's current roster draft and its named view presets together. */
final class CommandCompanionViewStore {
    static final String ALL_ID = "__all__";
    static final String SELECTED_ID = "__selected__";
    private static final String METADATA_KEY = "Tamework.Command.CompanionViews";
    private static final String CURRENT = "current";
    private static final String SELECTED_ID_KEY = "selectedId";
    private static final String VIEWS = "views";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String STATE = "state";
    private static final String NEARBY = "nearby";
    private static final String SEARCH = "search";
    private static final String SORT = "sort";
    private static final String SELECTED_ONLY = "selectedOnly";
    private static final String SPECIES_IDS = "speciesIds";
    private static final String GROUP_IDS = "groupIds";
    private static final int MAX_VIEWS = 16;
    private static final int MAX_NAME_LENGTH = 40;

    private CommandCompanionViewStore() { }

    static Snapshot read(ItemStack stack) {
        BsonDocument stored = viewDocument(stack);
        if (stored == null) {
            return new Snapshot(legacyCurrent(stack), "", List.of());
        }
        CompanionViewSettings current = settings(stored.get(CURRENT));
        if (current == null) {
            current = legacyCurrent(stack);
        }
        List<SavedView> views = views(stored.get(VIEWS));
        String selectedId = selectedId(stored.get(SELECTED_ID_KEY), views);
        return new Snapshot(current, selectedId, views);
    }

    static ItemStack writeCurrent(ItemStack stack, CompanionViewSettings settings) {
        if (stack == null) {
            return null;
        }
        Snapshot snapshot = read(stack);
        return writeAndSynchronize(stack, new Snapshot(
                normalize(settings), snapshot.selectedId(), snapshot.views()));
    }

    static ItemStack choose(ItemStack stack, String id) {
        if (stack == null || id == null) {
            return stack;
        }
        Snapshot snapshot = read(stack);
        CompanionViewSettings chosen;
        if (ALL_ID.equals(id)) {
            chosen = CompanionViewSettings.defaults().withState("All");
        } else if (SELECTED_ID.equals(id)) {
            chosen = CompanionViewSettings.defaults().withState("All")
                    .withExtraFilters(true, List.of(), List.of());
        } else {
            chosen = snapshot.views().stream().filter(view -> view.id().equals(id))
                    .map(SavedView::settings).findFirst().orElse(null);
            if (chosen == null) {
                return stack;
            }
        }
        return writeAndSynchronize(stack, new Snapshot(chosen, id, snapshot.views()));
    }

    static ItemStack save(ItemStack stack, String name, boolean updateSelected) {
        if (stack == null) {
            return null;
        }
        String normalizedName = name(name);
        if (normalizedName.isEmpty() && !updateSelected) {
            return stack;
        }
        Snapshot snapshot = read(stack);
        if (updateSelected) {
            for (int index = 0; index < snapshot.views().size(); index++) {
                SavedView view = snapshot.views().get(index);
                if (view.id().equals(snapshot.selectedId())) {
                    ArrayList<SavedView> updated = new ArrayList<>(snapshot.views());
                    updated.set(index, new SavedView(view.id(), view.name(), snapshot.current()));
                    return writeAndSynchronize(stack, new Snapshot(snapshot.current(), view.id(), updated));
                }
            }
            return stack;
        }
        if (snapshot.views().size() >= MAX_VIEWS) {
            return stack;
        }
        SavedView created = new SavedView(UUID.randomUUID().toString(), normalizedName, snapshot.current());
        ArrayList<SavedView> updated = new ArrayList<>(snapshot.views());
        updated.add(created);
        return writeAndSynchronize(stack, new Snapshot(snapshot.current(), created.id(), updated));
    }

    static ItemStack rename(ItemStack stack, String name) {
        if (stack == null) {
            return null;
        }
        String normalizedName = name(name);
        if (normalizedName.isEmpty()) {
            return stack;
        }
        Snapshot snapshot = read(stack);
        for (int index = 0; index < snapshot.views().size(); index++) {
            SavedView view = snapshot.views().get(index);
            if (view.id().equals(snapshot.selectedId())) {
                ArrayList<SavedView> updated = new ArrayList<>(snapshot.views());
                updated.set(index, new SavedView(view.id(), normalizedName, view.settings()));
                return writeAndSynchronize(stack, new Snapshot(snapshot.current(), view.id(), updated));
            }
        }
        return stack;
    }

    static ItemStack deleteSelected(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        Snapshot snapshot = read(stack);
        ArrayList<SavedView> updated = new ArrayList<>(snapshot.views());
        if (!updated.removeIf(view -> view.id().equals(snapshot.selectedId()))) {
            return stack;
        }
        CompanionViewSettings all = CompanionViewSettings.defaults().withState("All");
        return writeAndSynchronize(stack, new Snapshot(all, ALL_ID, updated));
    }

    private static ItemStack writeAndSynchronize(ItemStack stack, Snapshot snapshot) {
        ItemStack updated = write(stack, snapshot);
        CompanionViewSettings current = snapshot.current();
        updated = CommandCompanionPreferences.state(updated, current.state());
        updated = CommandCompanionPreferences.nearby(updated, current.nearby());
        CommandPanelPreferenceService preferences = new CommandPanelPreferenceService();
        updated = preferences.setSort(updated, current.sort());
        return current.search().isEmpty()
                ? preferences.clearFilters(updated)
                : preferences.setNameFilter(updated, current.search());
    }

    private static ItemStack write(ItemStack stack, Snapshot snapshot) {
        BsonDocument metadata = stack.getMetadata();
        BsonDocument copy = metadata == null ? new BsonDocument() : metadata.clone();
        copy.put(METADATA_KEY, document(snapshot));
        return stack.withMetadata(copy);
    }

    private static BsonDocument document(Snapshot snapshot) {
        BsonDocument document = new BsonDocument();
        document.put(CURRENT, document(snapshot.current()));
        document.put(SELECTED_ID_KEY, new BsonString(snapshot.selectedId()));
        BsonArray views = new BsonArray();
        for (SavedView view : snapshot.views()) {
            BsonDocument saved = document(view.settings());
            saved.put(ID, new BsonString(view.id()));
            saved.put(NAME, new BsonString(view.name()));
            views.add(saved);
        }
        document.put(VIEWS, views);
        return document;
    }

    private static BsonDocument document(CompanionViewSettings settings) {
        BsonDocument document = new BsonDocument();
        document.put(STATE, new BsonString(settings.state()));
        document.put(NEARBY, new BsonBoolean(settings.nearby()));
        document.put(SEARCH, new BsonString(settings.search()));
        document.put(SORT, new BsonString(settings.sort()));
        document.put(SELECTED_ONLY, new BsonBoolean(settings.selectedOnly()));
        document.put(SPECIES_IDS, array(settings.speciesIds()));
        document.put(GROUP_IDS, array(settings.groupIds()));
        return document;
    }

    private static BsonArray array(List<String> values) {
        BsonArray result = new BsonArray();
        for (String value : values) {
            result.add(new BsonString(value));
        }
        return result;
    }

    private static BsonDocument viewDocument(ItemStack stack) {
        if (stack == null || stack.getMetadata() == null) {
            return null;
        }
        BsonValue value = stack.getMetadata().get(METADATA_KEY);
        return value != null && value.isDocument() ? value.asDocument() : null;
    }

    private static CompanionViewSettings legacyCurrent(ItemStack stack) {
        CommandPanelPreferenceService preferences = new CommandPanelPreferenceService();
        return new CompanionViewSettings(
                CommandCompanionPreferences.state(stack),
                CommandCompanionPreferences.nearby(stack),
                preferences.resolveNameFilter(stack),
                preferences.resolveSortValue(stack),
                false,
                List.of(),
                List.of());
    }

    private static CompanionViewSettings settings(BsonValue value) {
        if (value == null || !value.isDocument()) {
            return null;
        }
        BsonDocument document = value.asDocument();
        return new CompanionViewSettings(
                string(document.get(STATE)),
                bool(document.get(NEARBY)),
                string(document.get(SEARCH)),
                string(document.get(SORT)),
                bool(document.get(SELECTED_ONLY)),
                strings(document.get(SPECIES_IDS)),
                strings(document.get(GROUP_IDS)));
    }

    private static List<SavedView> views(BsonValue value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        ArrayList<SavedView> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (BsonValue raw : value.asArray()) {
            if (result.size() == MAX_VIEWS || raw == null || !raw.isDocument()) {
                continue;
            }
            BsonDocument document = raw.asDocument();
            String id = string(document.get(ID));
            String name = name(string(document.get(NAME)));
            CompanionViewSettings settings = settings(raw);
            if (!validId(id) || name.isEmpty() || settings == null || !ids.add(id)) {
                continue;
            }
            result.add(new SavedView(id, name, settings));
        }
        return List.copyOf(result);
    }

    private static String selectedId(BsonValue value, List<SavedView> views) {
        String id = string(value);
        if (id.isEmpty() || ALL_ID.equals(id) || SELECTED_ID.equals(id)
                || views.stream().anyMatch(view -> view.id().equals(id))) {
            return id;
        }
        return ALL_ID;
    }

    private static boolean validId(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String string(BsonValue value) {
        return value != null && value.isString() ? value.asString().getValue() : "";
    }

    private static boolean bool(BsonValue value) {
        return value != null && value.isBoolean() && value.asBoolean().getValue();
    }

    private static List<String> strings(BsonValue value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        for (BsonValue entry : value.asArray()) {
            if (entry != null && entry.isString()) {
                result.add(entry.asString().getValue());
            }
        }
        return result;
    }

    private static CompanionViewSettings normalize(CompanionViewSettings settings) {
        return settings == null ? CompanionViewSettings.defaults() : settings;
    }

    private static String name(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }

    record Snapshot(CompanionViewSettings current, String selectedId, List<SavedView> views) {
        Snapshot {
            current = normalize(current);
            selectedId = selectedId == null ? ALL_ID : selectedId;
            views = List.copyOf(views == null ? List.of() : views);
        }
    }

    record SavedView(String id, String name, CompanionViewSettings settings) {
        SavedView {
            settings = normalize(settings);
        }
    }
}
