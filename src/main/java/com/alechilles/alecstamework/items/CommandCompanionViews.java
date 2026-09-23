package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.components.TameworkCompanionViewsComponent;
import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/** Player-saved definitions shared by ordinary command flutes. Access only on the player's world thread. */
final class CommandCompanionViews {
    private static final String VIEWS = "views";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final int MAX_VIEWS = 16;
    private static final int MAX_NAME_LENGTH = 40;

    private CommandCompanionViews() { }

    /** Null means the player's component store is unavailable; an absent component is an empty library. */
    @Nullable
    static List<View> read(@Nullable Player player) {
        var type = TameworkCompanionViewsComponent.getComponentType();
        if (player == null || player.getWorld() == null || type == null) return null;
        var world = player.getWorld();
        var ref = world.getEntityRef(player.getUuid());
        if (ref == null || !ref.isValid()) return null;
        var component = world.getEntityStore().getStore().getComponent(ref, type);
        return component == null ? List.of() : decode(component.views());
    }

    @Nullable
    static String saveNew(Player player, String name, CompanionViewSettings settings) {
        String normalized = name(name);
        List<View> current = read(player);
        if (normalized.isEmpty() || current == null || current.size() >= MAX_VIEWS) return null;
        String id = UUID.randomUUID().toString();
        ArrayList<View> updated = new ArrayList<>(current);
        updated.add(new View(id, normalized, settings));
        return write(player, updated) ? id : null;
    }

    static boolean update(Player player, String id, CompanionViewSettings settings) {
        List<View> current = read(player);
        if (current == null) return false;
        for (int index = 0; index < current.size(); index++) {
            View view = current.get(index);
            if (!view.id().equals(id)) continue;
            ArrayList<View> updated = new ArrayList<>(current);
            updated.set(index, new View(id, view.name(), settings));
            return write(player, updated);
        }
        return false;
    }

    static boolean rename(Player player, String id, String name) {
        String normalized = name(name);
        List<View> current = read(player);
        if (normalized.isEmpty() || current == null) return false;
        for (int index = 0; index < current.size(); index++) {
            View view = current.get(index);
            if (!view.id().equals(id)) continue;
            ArrayList<View> updated = new ArrayList<>(current);
            updated.set(index, new View(id, normalized, view.settings()));
            return write(player, updated);
        }
        return false;
    }

    static boolean delete(Player player, String id) {
        List<View> current = read(player);
        if (current == null) return false;
        ArrayList<View> updated = new ArrayList<>(current);
        if (!updated.removeIf(view -> view.id().equals(id))) return false;
        return write(player, updated);
    }

    @Nullable
    static View find(@Nullable List<View> views, String id) {
        if (views == null || id == null) return null;
        for (View view : views) if (view.id().equals(id)) return view;
        return null;
    }

    private static boolean write(Player player, List<View> views) {
        var type = TameworkCompanionViewsComponent.getComponentType();
        if (player == null || player.getWorld() == null || type == null) return false;
        var world = player.getWorld();
        var ref = world.getEntityRef(player.getUuid());
        if (ref == null || !ref.isValid()) return false;
        var store = world.getEntityStore().getStore();
        var previous = store.getComponent(ref, type);
        var next = previous == null ? new TameworkCompanionViewsComponent() : previous.clone();
        next.views(encode(views));
        // Called from menu/item interaction callbacks, never inside an ECS system iteration.
        store.putComponent(ref, type, next);
        return true;
    }

    static String encode(List<View> views) {
        BsonArray entries = new BsonArray();
        for (View view : views) {
            BsonDocument entry = CommandCompanionViewStore.encodeSettings(view.settings());
            entry.put(ID, new BsonString(view.id()));
            entry.put(NAME, new BsonString(view.name()));
            entries.add(entry);
        }
        return new BsonDocument(VIEWS, entries).toJson();
    }

    @Nullable
    static List<View> decode(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            BsonValue entries = BsonDocument.parse(raw).get(VIEWS);
            if (entries == null || !entries.isArray() || entries.asArray().size() > MAX_VIEWS) return null;
            ArrayList<View> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (BsonValue value : entries.asArray()) {
                if (value == null || !value.isDocument()) return null;
                BsonDocument entry = value.asDocument();
                String id = entry.getString(ID, new BsonString("")).getValue();
                String name = entry.getString(NAME, new BsonString("")).getValue();
                CompanionViewSettings settings = CommandCompanionViewStore.decodeSettings(entry);
                if (!validId(id) || !ids.add(id) || !name.equals(name(name)) || settings == null) return null;
                result.add(new View(id, name, settings));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean validId(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String name(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }

    record View(String id, String name, CompanionViewSettings settings) { }
}
