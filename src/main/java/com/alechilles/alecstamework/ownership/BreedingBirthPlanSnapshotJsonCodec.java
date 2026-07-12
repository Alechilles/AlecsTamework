package com.alechilles.alecstamework.ownership;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Versioned JSON codec for breeding plans embedded in population journal context. */
final class BreedingBirthPlanSnapshotJsonCodec {
    private static final int VERSION = 1;

    private BreedingBirthPlanSnapshotJsonCodec() {
    }

    @Nonnull
    static JsonObject encode(@Nonnull BreedingBirthPlanSnapshot snapshot) {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        JsonObject fertility = new JsonObject();
        fertility.addProperty("parentAMultiplier", snapshot.parentAMultiplier());
        fertility.addProperty("parentBMultiplier", snapshot.parentBMultiplier());
        fertility.addProperty("expectedOffspring", snapshot.expectedOffspring());
        fertility.addProperty("offspringCount", snapshot.offspringCount());
        json.add("fertility", fertility);
        JsonArray children = new JsonArray();
        for (BreedingBirthPlanSnapshot.PlannedChild child : snapshot.children()) {
            JsonObject childJson = new JsonObject();
            childJson.addProperty("childKey", child.childKey());
            childJson.addProperty("roleId", child.roleId());
            childJson.addProperty("roleIndex", child.roleIndex());
            addOptional(childJson, "adultRoleId", child.adultRoleId());
            addOptional(childJson, "gender", child.gender());
            childJson.addProperty("lifecycleFamilyPresent", child.lifecycleFamilyPresent());
            addOptional(childJson, "lifecycleFamilyId", child.lifecycleFamilyId());
            addOptional(childJson, "lifecycleLineId", child.lifecycleLineId());
            addOptional(childJson, "ownerId", child.ownerId() == null ? null : child.ownerId().toString());
            addOptional(childJson, "ownerName", child.ownerName());
            childJson.addProperty("populationType", child.populationType());
            children.add(childJson);
        }
        json.add("children", children);
        return json;
    }

    @Nullable
    static BreedingBirthPlanSnapshot decode(@Nullable JsonElement element) {
        try {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject json = element.getAsJsonObject();
            if (requiredInt(json, "version") != VERSION) {
                return null;
            }
            JsonObject fertility = requiredObject(json, "fertility");
            JsonArray childrenJson = requiredArray(json, "children");
            List<BreedingBirthPlanSnapshot.PlannedChild> children = new ArrayList<>(childrenJson.size());
            for (JsonElement childElement : childrenJson) {
                JsonObject child = childElement.getAsJsonObject();
                String ownerId = optionalText(child, "ownerId");
                children.add(new BreedingBirthPlanSnapshot.PlannedChild(
                        requiredText(child, "childKey"),
                        requiredText(child, "roleId"),
                        requiredInt(child, "roleIndex"),
                        optionalText(child, "adultRoleId"),
                        optionalText(child, "gender"),
                        child.get("lifecycleFamilyPresent").getAsBoolean(),
                        optionalText(child, "lifecycleFamilyId"),
                        optionalText(child, "lifecycleLineId"),
                        ownerId == null ? null : UUID.fromString(ownerId),
                        optionalText(child, "ownerName"),
                        requiredText(child, "populationType")
                ));
            }
            return new BreedingBirthPlanSnapshot(
                    requiredDouble(fertility, "parentAMultiplier"),
                    requiredDouble(fertility, "parentBMultiplier"),
                    requiredDouble(fertility, "expectedOffspring"),
                    requiredInt(fertility, "offspringCount"),
                    children
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void addOptional(JsonObject json, String key, @Nullable String value) {
        if (value == null) {
            json.add(key, null);
        } else {
            json.addProperty(key, value);
        }
    }

    @Nonnull
    private static JsonObject requiredObject(JsonObject json, String key) {
        return json.getAsJsonObject(key);
    }

    @Nonnull
    private static JsonArray requiredArray(JsonObject json, String key) {
        return json.getAsJsonArray(key);
    }

    @Nonnull
    private static String requiredText(JsonObject json, String key) {
        String value = optionalText(json, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    @Nullable
    private static String optionalText(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static int requiredInt(JsonObject json, String key) {
        return json.get(key).getAsInt();
    }

    private static double requiredDouble(JsonObject json, String key) {
        return json.get(key).getAsDouble();
    }
}
