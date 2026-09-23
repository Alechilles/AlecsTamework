package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/** Keeps the current draft and selected view ID on the physical flute. */
final class CommandCompanionViewStore {
    static final String ALL_ID = "__all__";
    static final String SELECTED_ID = "__selected__";
    private static final String METADATA_KEY = "Tamework.Command.CompanionViews";
    private static final String CURRENT = "current";
    private static final String SELECTED_ID_KEY = "selectedId";
    private static final String STATE = "state";
    private static final String NEARBY = "nearby";
    private static final String SEARCH = "search";
    private static final String SORT = "sort";
    private static final String SELECTED_ONLY = "selectedOnly";
    private static final String SPECIES_IDS = "speciesIds";
    private static final String GROUP_IDS = "groupIds";

    private CommandCompanionViewStore() { }

    static Snapshot read(@Nullable ItemStack stack) {
        BsonValue raw = stack == null || stack.getMetadata() == null ? null
                : stack.getMetadata().get(METADATA_KEY);
        BsonDocument stored = raw != null && raw.isDocument() ? raw.asDocument() : null;
        if (stored == null) return new Snapshot(legacyCurrent(stack), "");
        CompanionViewSettings current = decodeSettings(stored.get(CURRENT));
        if (current == null) current = legacyCurrent(stack);
        return new Snapshot(current, selectedId(stored.get(SELECTED_ID_KEY)));
    }

    static ItemStack writeCurrent(ItemStack stack, CompanionViewSettings settings) {
        if (stack == null) return null;
        return writeAndSynchronize(stack, new Snapshot(
                settings == null ? CompanionViewSettings.defaults() : settings,
                read(stack).selectedId()));
    }

    static ItemStack choose(ItemStack stack, String id, @Nullable CompanionViewSettings saved) {
        if (stack == null || id == null) return stack;
        CompanionViewSettings chosen;
        if (ALL_ID.equals(id)) {
            chosen = CompanionViewSettings.defaults().withState("All");
        } else if (SELECTED_ID.equals(id)) {
            chosen = CompanionViewSettings.defaults().withState("All")
                    .withExtraFilters(true, List.of(), List.of());
        } else {
            if (saved == null) return stack;
            chosen = saved;
        }
        return writeAndSynchronize(stack, new Snapshot(chosen, id));
    }

    private static ItemStack writeAndSynchronize(ItemStack stack, Snapshot snapshot) {
        BsonDocument metadata = stack.getMetadata();
        BsonDocument copy = metadata == null ? new BsonDocument() : metadata.clone();
        BsonDocument document = new BsonDocument();
        document.put(CURRENT, encodeSettings(snapshot.current()));
        document.put(SELECTED_ID_KEY, new BsonString(snapshot.selectedId()));
        copy.put(METADATA_KEY, document);
        ItemStack updated = stack.withMetadata(copy);
        CompanionViewSettings current = snapshot.current();
        updated = CommandCompanionPreferences.state(updated, current.state());
        updated = CommandCompanionPreferences.nearby(updated, current.nearby());
        CommandPanelPreferenceService preferences = new CommandPanelPreferenceService();
        updated = preferences.setSort(updated, current.sort());
        return current.search().isEmpty()
                ? preferences.clearFilters(updated)
                : preferences.setNameFilter(updated, current.search());
    }

    static BsonDocument encodeSettings(CompanionViewSettings settings) {
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

    @Nullable
    static CompanionViewSettings decodeSettings(BsonValue value) {
        if (value == null || !value.isDocument()) return null;
        BsonDocument document = value.asDocument();
        return new CompanionViewSettings(
                string(document.get(STATE)), bool(document.get(NEARBY)),
                string(document.get(SEARCH)), string(document.get(SORT)),
                bool(document.get(SELECTED_ONLY)), strings(document.get(SPECIES_IDS)),
                strings(document.get(GROUP_IDS)));
    }

    private static CompanionViewSettings legacyCurrent(@Nullable ItemStack stack) {
        CommandPanelPreferenceService preferences = new CommandPanelPreferenceService();
        return new CompanionViewSettings(
                CommandCompanionPreferences.state(stack),
                CommandCompanionPreferences.nearby(stack),
                preferences.resolveNameFilter(stack),
                preferences.resolveSortValue(stack), false, List.of(), List.of());
    }

    private static String selectedId(BsonValue value) {
        String id = string(value);
        if (id.isEmpty() || ALL_ID.equals(id) || SELECTED_ID.equals(id)) return id;
        try {
            UUID.fromString(id);
            return id;
        } catch (IllegalArgumentException ignored) {
            return ALL_ID;
        }
    }

    private static BsonArray array(List<String> values) {
        BsonArray result = new BsonArray();
        for (String value : values) result.add(new BsonString(value));
        return result;
    }

    private static String string(BsonValue value) {
        return value != null && value.isString() ? value.asString().getValue() : "";
    }

    private static boolean bool(BsonValue value) {
        return value != null && value.isBoolean() && value.asBoolean().getValue();
    }

    private static List<String> strings(BsonValue value) {
        if (value == null || !value.isArray()) return List.of();
        ArrayList<String> result = new ArrayList<>();
        for (BsonValue entry : value.asArray()) {
            if (entry != null && entry.isString()) result.add(entry.asString().getValue());
        }
        return result;
    }

    record Snapshot(CompanionViewSettings current, String selectedId) { }
}
