package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Encodes durable coop resident state snapshots without coupling ledger orchestration to JSON details.
 */
final class CoopResidentSnapshotJsonCodec {
    private static final String SNAPSHOT_VERSION = "1";

    private final Gson json = new Gson();

    @Nullable
    String serialize(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return null;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("version", SNAPSHOT_VERSION);
            payload.addProperty("npcUuid", snapshot.npcUuid().toString());
            payload.addProperty("coopId", snapshot.coopId());
            payload.addProperty("residentSlot", snapshot.residentSlot());
            payload.addProperty("roleId", snapshot.roleId());
            payload.addProperty("capturedAtMs", snapshot.capturedAtMs());
            putComponentJson(payload, "commandLinks", snapshot.commandLinks(), TameworkCommandLinksComponent.class);
            putComponentJson(payload, "owner", snapshot.owner(), TameworkOwnerComponent.class);
            putComponentJson(payload, "tamed", snapshot.tamed(), TameworkTamedComponent.class);
            putComponentJson(payload, "npcName", snapshot.npcName(), TameworkNpcNameComponent.class);
            putComponentJson(payload, "happiness", snapshot.happiness(), TameworkHappinessComponent.class);
            putComponentJson(payload, "needs", snapshot.needs(), TameworkNeedsComponent.class);
            putComponentJson(payload, "breeding", snapshot.breeding(), TameworkBreedingComponent.class);
            putComponentJson(payload, "leveling", snapshot.leveling(), TameworkLevelingComponent.class);
            putComponentJson(payload, "traits", snapshot.traits(), TameworkTraitsComponent.class);
            putComponentJson(payload, "talents", snapshot.talents(), TameworkTalentsComponent.class);
            putComponentJson(payload, "lifeStage", snapshot.lifeStage(), TameworkLifeStageComponent.class);
            putComponentJson(payload, "attachments", snapshot.attachments(), TameworkAttachmentsComponent.class);
            if (snapshot.healthPercent() != null) {
                payload.addProperty("healthPercent", snapshot.healthPercent());
            }
            return payload.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    CoopResidentStateSnapshotService.CoopResidentStateSnapshot deserialize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject payload = parsed.getAsJsonObject();
            String version = getJsonString(payload, "version");
            if (version != null && !SNAPSHOT_VERSION.equals(version)) {
                return null;
            }
            UUID npcUuid = parseUuid(getJsonString(payload, "npcUuid"));
            if (npcUuid == null) {
                return null;
            }
            String coopId = normalizeIdentifier(getJsonString(payload, "coopId"));
            int residentSlot = getJsonInt(payload, "residentSlot", -1);
            String roleId = normalizeIdentifier(getJsonString(payload, "roleId"));
            long capturedAtMs = Math.max(0L, getJsonLong(payload, "capturedAtMs", 0L));
            TameworkCommandLinksComponent commandLinks =
                    parseComponent(payload, "commandLinks", TameworkCommandLinksComponent.class);
            TameworkOwnerComponent owner = parseComponent(payload, "owner", TameworkOwnerComponent.class);
            TameworkTamedComponent tamed = parseComponent(payload, "tamed", TameworkTamedComponent.class);
            TameworkNpcNameComponent npcName = parseComponent(payload, "npcName", TameworkNpcNameComponent.class);
            TameworkHappinessComponent happiness =
                    parseComponent(payload, "happiness", TameworkHappinessComponent.class);
            TameworkNeedsComponent needs = parseComponent(payload, "needs", TameworkNeedsComponent.class);
            TameworkBreedingComponent breeding =
                    parseComponent(payload, "breeding", TameworkBreedingComponent.class);
            TameworkLevelingComponent leveling =
                    parseComponent(payload, "leveling", TameworkLevelingComponent.class);
            TameworkTraitsComponent traits = parseComponent(payload, "traits", TameworkTraitsComponent.class);
            TameworkTalentsComponent talents = parseComponent(payload, "talents", TameworkTalentsComponent.class);
            TameworkLifeStageComponent lifeStage =
                    parseComponent(payload, "lifeStage", TameworkLifeStageComponent.class);
            TameworkAttachmentsComponent attachments =
                    parseComponent(payload, "attachments", TameworkAttachmentsComponent.class);
            Double healthPercent = getJsonDouble(payload, "healthPercent");
            return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                    npcUuid,
                    coopId,
                    residentSlot,
                    roleId,
                    commandLinks,
                    owner,
                    tamed,
                    npcName,
                    happiness,
                    needs,
                    breeding,
                    leveling,
                    traits,
                    talents,
                    lifeStage,
                    attachments,
                    healthPercent,
                    capturedAtMs
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T> void putComponentJson(@Nonnull JsonObject payload,
                                      @Nonnull String field,
                                      @Nullable T component,
                                      @Nonnull Class<T> componentClass) {
        if (component == null) {
            return;
        }
        JsonElement value = json.toJsonTree(component, componentClass);
        if (value != null && value.isJsonObject()) {
            payload.add(field, value.getAsJsonObject());
        }
    }

    @Nullable
    private <T> T parseComponent(@Nonnull JsonObject payload,
                                 @Nonnull String field,
                                 @Nonnull Class<T> componentClass) {
        if (!payload.has(field) || !payload.get(field).isJsonObject()) {
            return null;
        }
        try {
            return json.fromJson(payload.getAsJsonObject(field), componentClass);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String getJsonString(@Nonnull JsonObject payload, @Nonnull String key) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = payload.get(key).getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int getJsonInt(@Nonnull JsonObject payload, @Nonnull String key, int fallback) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return payload.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long getJsonLong(@Nonnull JsonObject payload, @Nonnull String key, long fallback) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return payload.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private Double getJsonDouble(@Nonnull JsonObject payload, @Nonnull String key) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        try {
            double value = payload.get(key).getAsDouble();
            return Double.isFinite(value) ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
