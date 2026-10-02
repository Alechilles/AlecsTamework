package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Reads the companion state the old runtime stored as JSON: allow-list state snapshots (plain,
 * or inside a death or bonded wrapper) and per-alias entity checkpoints. Pure; every reader
 * returns null for a payload it cannot use, so the mapper can fall back.
 *
 * <p>Summaries are built with {@link CompanionSummaries#build}, the same clamp a live body's
 * summary goes through. The role's name key, the icon and the harvest alarm need engine or
 * config lookups and stay unset, and a checkpoint stores no maximum health, so its health stays
 * unset; the first sighting or restore of the body fills them.</p>
 */
final class LegacyState {
    private static final CoopResidentStateSnapshotCodec STATE_CODEC = new CoopResidentStateSnapshotCodec();

    /**
     * One readable state snapshot.
     *
     * @param json the plain {@code CoopResidentStateSnapshot} JSON, with no wrapper around it
     */
    record State(@Nonnull String json, @Nonnull UUID npcUuid, @Nonnull CompanionSummary summary,
                 @Nullable String customName) {
    }

    /** Wall-clock death timers of an old death snapshot; {@code cause} as the death flow writes it. */
    record Death(long diedAtMs, long reviveAvailableAtMs, @Nullable String cause) {
    }

    /**
     * One readable entity checkpoint.
     *
     * @param entity the serialized entity, the same document a format 1 snapshot holds; for a
     *               dying body, with its death state removed ({@code SnapshotPatch.forRevive})
     * @param dying  whether the body had a death component when the checkpoint was taken
     */
    record Checkpoint(@Nonnull BsonDocument entity, @Nullable String worldKey, double x, double y, double z,
                      long capturedAtMs, @Nonnull CompanionSummary summary, @Nullable String customName,
                      boolean dying) {
    }

    private LegacyState() {
    }

    /**
     * The state of a {@code companion_snapshot} payload, or of the decoded bonded payload: the
     * {@code fullState} object when there is one (death and bonded wrappers), else the payload itself.
     */
    @Nullable
    static State state(@Nonnull String payloadJson) {
        JsonObject root = object(payloadJson);
        if (root == null) {
            return null;
        }
        JsonElement full = root.get("fullState");
        String json = full != null && full.isJsonObject() ? full.toString() : payloadJson;
        CoopResidentStateSnapshot state;
        try {
            state = STATE_CODEC.decode(json).snapshotOrNull();
        } catch (RuntimeException | LinkageError unreadable) {
            state = null;
        }
        if (state == null) {
            return null;
        }
        return new State(json, state.npcUuid(), summary(state), customName(state.npcName()));
    }

    /** The death timers of a death snapshot payload; null when it has no {@code diedAtMs}. */
    @Nullable
    static Death death(@Nonnull String payloadJson) {
        JsonObject root = object(payloadJson);
        Long diedAtMs = root == null ? null : number(root, "diedAtMs");
        if (diedAtMs == null) {
            return null;
        }
        Long reviveAtMs = number(root, "respawnAvailableAtMs");
        String kind = string(root, "deathCauseKind");
        String source = string(root, "deathSourceName");
        return new Death(diedAtMs, reviveAtMs == null ? 0L : reviveAtMs,
                kind == null || source == null ? kind : kind + ":" + source);
    }

    /** The text inside a bonded {@code {encoding, payload}} envelope; null when it is not one. */
    @Nullable
    static String unwrapBonded(@Nonnull String envelopeJson) {
        JsonObject root = object(envelopeJson);
        String encoding = root == null ? null : string(root, "encoding");
        String payload = root == null ? null : string(root, "payload");
        if (encoding == null || payload == null) {
            return null;
        }
        try {
            return switch (encoding) {
                case "base64" -> new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
                case "hex-utf8" -> new String(HexFormat.of().parseHex(payload), StandardCharsets.UTF_8);
                default -> null;
            };
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** A state that holds nothing but its NPC UUID: a restore from it builds the body from the role. */
    @Nonnull
    static String emptyState(@Nonnull UUID npcUuid) {
        JsonObject state = new JsonObject();
        state.addProperty("npcUuid", npcUuid.toString());
        return state.toString();
    }

    /** An entity checkpoint row's payload. The old runtime wrote its numbers as JSON strings. */
    @Nullable
    static Checkpoint checkpoint(@Nonnull String jsonPayload, long fallbackCapturedAtMs) {
        JsonObject root = object(jsonPayload);
        String holder = root == null ? null : string(root, "holderExtendedJson");
        if (holder == null) {
            return null;
        }
        BsonDocument entity;
        try {
            entity = BsonDocument.parse(holder);
        } catch (RuntimeException malformed) {
            return null;
        }
        Long capturedAtMs = number(root, "capturedAtMs");
        long observedAtMs = capturedAtMs == null ? fallbackCapturedAtMs : capturedAtMs;
        BsonDocument components = entity.isDocument("Components") ? entity.getDocument("Components") : new BsonDocument();
        TameworkNpcNameComponent name = component(components, "TameworkNpcName", TameworkNpcNameComponent.CODEC);
        String roleId = components.isDocument("NPC") && components.getDocument("NPC").isString("RoleName")
                ? components.getDocument("NPC").getString("RoleName").getValue() : null;
        CompanionSummary summary = summary(roleId, name,
                component(components, "TameworkHappiness", TameworkHappinessComponent.CODEC),
                component(components, "TameworkNeeds", TameworkNeedsComponent.CODEC),
                component(components, "TameworkBreeding", TameworkBreedingComponent.CODEC),
                component(components, "TameworkLeveling", TameworkLevelingComponent.CODEC),
                component(components, "TameworkTalents", TameworkTalentsComponent.CODEC),
                component(components, "TameworkTraits", TameworkTraitsComponent.CODEC),
                component(components, "TameworkLifeStage", TameworkLifeStageComponent.CODEC),
                0f, 0f, observedAtMs);
        // A checkpoint taken while the body was dying would only serve a revive, and its record is
        // LIVE, so the death state is taken out the way a revive does: a recover can then use it.
        boolean dying = SnapshotPatch.isDeathSnapshot(entity);
        return new Checkpoint(dying ? SnapshotPatch.forRevive(entity) : entity, string(root, "worldKey"),
                coordinate(root, "x"), coordinate(root, "y"), coordinate(root, "z"), observedAtMs, summary,
                customName(name), dying);
    }

    private static CompanionSummary summary(CoopResidentStateSnapshot state) {
        Double current = state.currentHealth();
        Double maximum = state.maximumHealth();
        return summary(state.roleId(), state.npcName(), state.happiness(), state.needs(), state.breeding(),
                state.leveling(), state.talents(), state.traits(), state.lifeStage(),
                current == null ? 0f : current.floatValue(), maximum == null ? 0f : maximum.floatValue(),
                state.capturedAtMs());
    }

    /** The same inputs {@code CompanionSummaries.capture} reads from a live body, from decoded components. */
    private static CompanionSummary summary(@Nullable String roleId, @Nullable TameworkNpcNameComponent name,
                                            @Nullable TameworkHappinessComponent happiness,
                                            @Nullable TameworkNeedsComponent needs,
                                            @Nullable TameworkBreedingComponent breeding,
                                            @Nullable TameworkLevelingComponent leveling,
                                            @Nullable TameworkTalentsComponent talents,
                                            @Nullable TameworkTraitsComponent traits,
                                            @Nullable TameworkLifeStageComponent lifeStage,
                                            float healthCurrent, float healthMax, long observedAtMs) {
        Map<String, Double> traitValues = new LinkedHashMap<>();
        if (traits != null && traits.getTraitValues() != null) {
            for (TameworkTraitsComponent.TraitValue value : traits.getTraitValues()) {
                if (value != null && value.getId() != null) {
                    traitValues.put(value.getId(), value.getValue());
                }
            }
        }
        return CompanionSummaries.build(new CompanionSummaries.Inputs(customName(name), null,
                roleId == null || roleId.isBlank() ? null : roleId, null, healthCurrent, healthMax,
                happiness == null ? null : happiness.getConfigId(), happiness == null ? 0.0 : happiness.getValue(),
                needs == null ? null : needs.getConfigId(), needs == null ? 0.0 : needs.getHunger(),
                needs == null ? 0.0 : needs.getThirst(),
                breeding != null, breeding != null && breeding.isEnabled(),
                breeding == null ? 0L : breeding.getCooldownUntilMs(),
                breeding == null ? 0L : breeding.getCooldownStartedAtMs(),
                breeding == null ? 0L : breeding.getCooldownDurationMs(),
                0L,
                leveling == null ? null : leveling.getConfigId(), leveling == null ? 0 : leveling.getLevel(),
                leveling == null ? 0.0 : leveling.getCurrentXp(), leveling == null ? 0.0 : leveling.getTotalXp(),
                talents == null ? 0 : talents.getSpentPoints(), traitValues, 0L, 0L,
                traits == null ? null : traits.getConfigId(), talents == null ? null : talents.getConfigId(),
                CompanionSummaries.progression(lifeStage)), observedAtMs);
    }

    @Nullable
    private static String customName(@Nullable TameworkNpcNameComponent name) {
        return name == null || name.getName() == null || name.getName().isBlank() ? null : name.getName().trim();
    }

    /** A component of a serialized entity; null when it is absent or its codec refuses it. */
    @Nullable
    private static <T> T component(BsonDocument components, String id, BuilderCodec<T> codec) {
        if (!components.isDocument(id)) {
            return null;
        }
        try {
            return codec.decode(components.getDocument(id), new ExtraInfo());
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    @Nullable
    private static JsonObject object(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @Nullable
    private static String string(JsonObject root, String field) {
        JsonElement value = root.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    /** A whole number written as a JSON number or as a string; the sign is kept. */
    @Nullable
    private static Long number(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static double coordinate(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            double coordinate = value.getAsDouble();
            return Double.isFinite(coordinate) ? coordinate : 0.0;
        } catch (RuntimeException notANumber) {
            return 0.0;
        }
    }
}
