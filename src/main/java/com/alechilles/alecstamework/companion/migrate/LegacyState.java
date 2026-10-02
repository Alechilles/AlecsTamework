package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.LegacyDeathV1Payload;
import com.alechilles.alecstamework.items.persistence.LegacyDeathV1SnapshotCodec;
import com.alechilles.alecstamework.items.persistence.LegacyLostV1Payload;
import com.alechilles.alecstamework.items.persistence.LegacyLostV1SnapshotCodec;
import com.alechilles.alecstamework.items.persistence.SnapshotVector3;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/**
 * Reads the companion state the old runtime stored as JSON: allow-list state snapshots (plain,
 * or inside a death or bonded wrapper) and per-alias entity checkpoints. Pure; every reader
 * returns null for a payload it cannot use, so the mapper can fall back.
 *
 * <p>Payload version 1 of the kinds {@code death} and {@code lost} is the shape the 2.x public
 * persistence wrote: a flat list of facts, with the companion's identity kept in the profile
 * tables. {@link #legacyState} rebuilds the full state from both, as the 4.x restore did
 * ({@code LegacyRestorationFullStateMapper}), but never refuses over a disagreement: the profile
 * rows win and unreadable details are left out, because the import must not drop saved state.
 * A version 1 {@code capture} payload holds no state at all; that state is in the capture item.
 *
 * <p>Summaries are built with {@link CompanionSummaries#build}, the same clamp a live body's
 * summary goes through. The role's name key, the icon and the harvest alarm need engine or
 * config lookups and stay unset, and a checkpoint stores no maximum health, so its health stays
 * unset; the first sighting or restore of the body fills them.</p>
 */
final class LegacyState {
    private static final CoopResidentStateSnapshotCodec STATE_CODEC = new CoopResidentStateSnapshotCodec();
    private static final LegacyDeathV1SnapshotCodec DEATH_V1 = new LegacyDeathV1SnapshotCodec();
    private static final LegacyLostV1SnapshotCodec LOST_V1 = new LegacyLostV1SnapshotCodec();
    static final String KIND_DEATH = "death";
    static final String KIND_LOST = "lost";
    static final String KIND_CAPTURE = "capture";
    private static final char BACKSLASH = 92;

    /**
     * What the profile tables say about a companion; a version 1 payload is completed from it.
     *
     * @param npcUuid the profile's current alias, else its profile id
     * @param toolIds every tool linked to the profile, of any link type
     */
    record Identity(@Nonnull UUID npcUuid, @Nonnull String roleId, @Nullable UUID ownerUuid,
                    @Nullable String ownerName, @Nullable String customName, @Nullable Boolean tamed,
                    @Nonnull List<String> toolIds) {
    }

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

    /**
     * The state of a version 1 {@code death} or {@code lost} payload, written as the plain state
     * JSON. A lost payload carries only a home position, so its state is the identity alone.
     * Null for any other kind and for a payload that is not JSON.
     */
    @Nullable
    static State legacyState(@Nonnull String kind, @Nonnull String payloadJson, long createdAtMs,
                             @Nonnull Identity identity) {
        try {
            CoopResidentStateSnapshot state;
            if (KIND_DEATH.equals(kind)) {
                state = deathState(decodeDeath(payloadJson), createdAtMs, identity);
            } else if (KIND_LOST.equals(kind)) {
                LegacyLostV1Payload lost = LOST_V1.decode(payloadJson);
                state = new CoopResidentStateSnapshot(identity.npcUuid(), null, -1, identity.roleId(),
                        commandLinks(identity, lost.homePosition()), owner(identity, null),
                        identity.tamed() == null ? null : new TameworkTamedComponent(identity.tamed()),
                        name(identity.customName(), identity.ownerUuid()), null, null, null, null, null, null,
                        null, null, null, createdAtMs);
            } else {
                return null;
            }
            return new State(STATE_CODEC.encode(state), state.npcUuid(), summary(state), customName(state.npcName()));
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** The codec refuses a death cause it does not know; the state must survive that, so the cause is dropped. */
    private static LegacyDeathV1Payload decodeDeath(String payloadJson) {
        try {
            return DEATH_V1.decode(payloadJson);
        } catch (IllegalArgumentException refused) {
            JsonObject root = object(payloadJson);
            if (root == null || root.remove("deathCauseKind") == null) {
                throw refused;
            }
            return DEATH_V1.decode(root.toString());
        }
    }

    private static CoopResidentStateSnapshot deathState(LegacyDeathV1Payload death, long createdAtMs,
                                                        Identity identity) {
        String role = death.roleId() != null && death.roleId().trim().equalsIgnoreCase(identity.roleId())
                ? death.roleId().trim() : identity.roleId();
        String customName = identity.customName() != null ? identity.customName() : death.customName();
        boolean happinessPresent = death.happinessConfigId() != null || death.happinessValue() != null
                || death.happinessLastUpdateMs() != 0L || death.breedingHappiness() != null;
        double happinessValue = death.happinessValue() != null ? death.happinessValue()
                : death.breedingHappiness() != null ? death.breedingHappiness() : 0.0;
        TameworkHappinessComponent happiness = happinessPresent ? new TameworkHappinessComponent(
                death.happinessConfigId(), happinessValue, death.happinessLastUpdateMs()) : null;
        boolean breedingPresent = death.breedingConfigId() != null || death.breedingHappiness() != null
                || death.breedingEnabled() || death.breedingCooldownUntilMs() != 0L
                || death.breedingLastPartnerUuid() != null;
        TameworkBreedingComponent breeding = breedingPresent ? new TameworkBreedingComponent(
                death.breedingConfigId(), happinessValue, death.happinessLastUpdateMs(), false,
                death.breedingEnabled(), death.breedingCooldownUntilMs(), death.breedingLastPartnerUuid(), 0L, 0L)
                : null;
        boolean levelingPresent = death.levelingConfigId() != null || death.levelingLevel() > 1
                || death.levelingTotalXp() != 0.0;
        TameworkLevelingComponent leveling = levelingPresent ? new TameworkLevelingComponent(
                death.levelingConfigId(), death.levelingLevel(), 0.0, death.levelingTotalXp(), 0L) : null;
        boolean traitsPresent = death.traitsConfigId() != null || death.traitsRollSeed() != 0L
                || death.traitsValues() != null;
        TameworkTraitsComponent traits = traitsPresent ? new TameworkTraitsComponent(
                death.traitsConfigId(), death.traitsRollSeed(), traitValues(death.traitsValues())) : null;
        boolean talentsPresent = death.talentsConfigId() != null || death.talentsSpentPoints() != 0
                || death.purchasedTalentIds() != null;
        TameworkTalentsComponent talents = talentsPresent ? new TameworkTalentsComponent(
                death.talentsConfigId(), death.talentsSpentPoints(), talentIds(death.purchasedTalentIds())) : null;
        boolean lifeStagePresent = death.lifeStage() != null || death.lifeStageBornAtMs() != 0L
                || death.lifeStageAdolescentAtMs() != 0L || death.lifeStageAdultAtMs() != 0L
                || death.lifeStageFullyGrownAtMs() != 0L || death.lifeStageGender() != null;
        TameworkLifeStageComponent lifeStage = null;
        if (lifeStagePresent) {
            lifeStage = new TameworkLifeStageComponent(death.lifeStage(), death.lifeStageBornAtMs(),
                    death.lifeStageAdolescentAtMs(), death.lifeStageAdultAtMs(), death.lifeStageFullyGrownAtMs(),
                    death.lifeStageBabyScale(), death.lifeStageAdolescentScale(),
                    death.lifeStageAdolescentSwitchScale(), death.lifeStageAdultStartScale(),
                    death.lifeStageAdultSwitchScale(), death.lifeStageAdultScale(),
                    death.lifeStageGrowthScalingEnabled());
            lifeStage.setGender(death.lifeStageGender());
        }
        Map<String, String> attachmentIds = attachmentIds(death.attachmentsValues());
        TameworkAttachmentsComponent attachments = death.attachmentsConfigId() == null && attachmentIds.isEmpty()
                ? null : new TameworkAttachmentsComponent(death.attachmentsConfigId(), attachmentIds);
        return new CoopResidentStateSnapshot(identity.npcUuid(), null, -1, role,
                commandLinks(identity, death.homePosition()), owner(identity, death.ownerName()),
                new TameworkTamedComponent(death.tamed()), name(customName, identity.ownerUuid()), happiness, null,
                breeding, leveling, traits, talents, lifeStage, attachments, null, createdAtMs);
    }

    private static TameworkCommandLinksComponent commandLinks(Identity identity, @Nullable SnapshotVector3 home) {
        return new TameworkCommandLinksComponent(identity.ownerUuid(), identity.toolIds().toArray(new String[0]),
                home == null ? null : new Vector3d(home.x(), home.y(), home.z()));
    }

    @Nullable
    private static TameworkOwnerComponent owner(Identity identity, @Nullable String payloadOwnerName) {
        String ownerName = identity.ownerName() != null ? identity.ownerName()
                : identity.ownerUuid() != null ? payloadOwnerName : null;
        return identity.ownerUuid() == null && ownerName == null
                ? null : new TameworkOwnerComponent(identity.ownerUuid(), ownerName);
    }

    @Nullable
    private static TameworkNpcNameComponent name(@Nullable String customName, @Nullable UUID ownerUuid) {
        return customName == null || customName.isBlank() ? null
                : new TameworkNpcNameComponent(customName, ownerUuid, 0L, TameworkNpcNameComponent.NameSource.System);
    }

    /** Version 1 trait values: a JSON array of {@code {id, value}}. Entries that are not that are left out. */
    private static TameworkTraitsComponent.TraitValue[] traitValues(@Nullable String raw) {
        List<TameworkTraitsComponent.TraitValue> values = new ArrayList<>();
        try {
            JsonElement parsed = raw == null || raw.isBlank() ? null : JsonParser.parseString(raw);
            for (JsonElement element : parsed != null && parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray()) {
                JsonObject trait = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
                String id = string(trait, "id");
                JsonElement value = trait.get("value");
                if (id != null && !id.isBlank() && value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble())) {
                    values.add(new TameworkTraitsComponent.TraitValue(id, value.getAsDouble()));
                }
            }
        } catch (RuntimeException malformed) {
            // Keep the values read so far.
        }
        return values.toArray(new TameworkTraitsComponent.TraitValue[0]);
    }

    /** Version 1 talent ids: separated by a bar, with a backslash escaping a bar or a backslash. */
    private static String[] talentIds(@Nullable String raw) {
        List<String> ids = new ArrayList<>();
        if (raw != null) {
            StringBuilder current = new StringBuilder();
            boolean escaping = false;
            for (int index = 0; index <= raw.length(); index++) {
                boolean end = index == raw.length();
                char value = end ? '|' : raw.charAt(index);
                if (escaping && !end) {
                    current.append(value);
                    escaping = false;
                } else if (value == BACKSLASH && !end) {
                    escaping = true;
                } else if (value == '|') {
                    String id = current.toString().trim();
                    current.setLength(0);
                    if (!id.isEmpty() && !ids.contains(id)) {
                        ids.add(id);
                    }
                } else {
                    current.append(value);
                }
            }
        }
        return ids.toArray(new String[0]);
    }

    /** Version 1 attachments: {@code key,value} pairs separated by {@code ;}, each token base64url text. */
    private static Map<String, String> attachmentIds(@Nullable String raw) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(";")) {
            int separator = part.indexOf(',');
            if (separator <= 0 || separator == part.length() - 1) {
                continue;
            }
            try {
                String key = new String(Base64.getUrlDecoder().decode(part.substring(0, separator)), StandardCharsets.UTF_8);
                String value = new String(Base64.getUrlDecoder().decode(part.substring(separator + 1)), StandardCharsets.UTF_8);
                if (!key.isBlank() && !value.isBlank()) {
                    ids.putIfAbsent(key, value);
                }
            } catch (IllegalArgumentException malformed) {
                // Skip this pair; the others still apply.
            }
        }
        return ids;
    }

    /** The {@code npcUuid} a payload names at its root or inside {@code fullState}; null when it names none. */
    @Nullable
    static UUID npcUuid(@Nonnull String payloadJson) {
        JsonObject root = object(payloadJson);
        if (root == null) {
            return null;
        }
        JsonElement full = root.get("fullState");
        String value = string(full != null && full.isJsonObject() ? full.getAsJsonObject() : root, "npcUuid");
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
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
