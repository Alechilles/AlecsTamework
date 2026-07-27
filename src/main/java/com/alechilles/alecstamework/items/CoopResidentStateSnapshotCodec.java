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
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Strict versioned JSON boundary for complete managed-coop resident snapshots.
 *
 * <p>Version 1 intentionally retains the original ledger JSON shape. Missing payloads are
 * distinguished from corrupt payloads so recovery callers never mistake failed state decoding
 * for an NPC that legitimately had no snapshot.</p>
 */
public final class CoopResidentStateSnapshotCodec {
    public static final String CURRENT_VERSION = "1";

    private final Gson gson;

    public CoopResidentStateSnapshotCodec() {
        this(new Gson());
    }

    CoopResidentStateSnapshotCodec(@Nonnull Gson gson) {
        this.gson = gson;
    }

    /** Encodes one complete snapshot using the backward-compatible version-1 ledger shape. */
    @Nonnull
    public String encode(@Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        if (snapshot.npcUuid() == null) {
            throw new IllegalArgumentException("Snapshot source NPC UUID is required");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("version", CURRENT_VERSION);
        payload.addProperty("npcUuid", snapshot.npcUuid().toString());
        payload.addProperty("coopId", snapshot.coopId());
        payload.addProperty("residentSlot", snapshot.residentSlot());
        payload.addProperty("roleId", snapshot.roleId());
        payload.addProperty("capturedAtMs", snapshot.capturedAtMs());
        putComponent(payload, "commandLinks", snapshot.commandLinks(), TameworkCommandLinksComponent.class);
        putComponent(payload, "owner", snapshot.owner(), TameworkOwnerComponent.class);
        putComponent(payload, "tamed", snapshot.tamed(), TameworkTamedComponent.class);
        putComponent(payload, "npcName", snapshot.npcName(), TameworkNpcNameComponent.class);
        putComponent(payload, "happiness", snapshot.happiness(), TameworkHappinessComponent.class);
        putComponent(payload, "needs", snapshot.needs(), TameworkNeedsComponent.class);
        putComponent(payload, "breeding", snapshot.breeding(), TameworkBreedingComponent.class);
        putComponent(payload, "leveling", snapshot.leveling(), TameworkLevelingComponent.class);
        putComponent(payload, "traits", snapshot.traits(), TameworkTraitsComponent.class);
        putComponent(payload, "talents", snapshot.talents(), TameworkTalentsComponent.class);
        putComponent(payload, "lifeStage", snapshot.lifeStage(), TameworkLifeStageComponent.class);
        putComponent(payload, "attachments", snapshot.attachments(), TameworkAttachmentsComponent.class);
        validateHealthPair(snapshot.currentHealth(), snapshot.maximumHealth());
        if (snapshot.currentHealth() != null) {
            payload.addProperty("currentHealth", snapshot.currentHealth());
            payload.addProperty("maximumHealth", snapshot.maximumHealth());
        }
        if (snapshot.healthPercent() != null) {
            if (!Double.isFinite(snapshot.healthPercent())) {
                throw new IllegalArgumentException("Snapshot healthPercent must be finite");
            }
            payload.addProperty("healthPercent", snapshot.healthPercent());
        }
        return payload.toString();
    }

    /**
     * Decodes a ledger payload without collapsing absence and corruption into the same outcome.
     */
    @Nonnull
    public DecodeResult decode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return DecodeResult.notFound();
        }
        final JsonObject payload;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed == null || !parsed.isJsonObject()) {
                return DecodeResult.failed(Failure.INVALID_ROOT, null, "Snapshot root must be a JSON object");
            }
            payload = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            return DecodeResult.failed(Failure.INVALID_JSON, null, ex.getClass().getSimpleName());
        }

        try {
            String version = optionalString(payload, "version");
            if (version != null && !CURRENT_VERSION.equals(version)) {
                return DecodeResult.failed(Failure.UNSUPPORTED_VERSION, "version", version);
            }
            UUID npcUuid = requiredSourceUuid(payload);
            String coopId = normalizeIdentifier(optionalString(payload, "coopId"));
            int residentSlot = optionalInt(payload, "residentSlot", -1);
            String roleId = normalizeRoleId(optionalString(payload, "roleId"));
            long capturedAtMs = optionalLong(payload, "capturedAtMs", 0L);
            TameworkCommandLinksComponent commandLinks = component(
                    payload, "commandLinks", TameworkCommandLinksComponent.class);
            TameworkOwnerComponent owner = component(payload, "owner", TameworkOwnerComponent.class);
            TameworkTamedComponent tamed = component(payload, "tamed", TameworkTamedComponent.class);
            TameworkNpcNameComponent npcName = component(payload, "npcName", TameworkNpcNameComponent.class);
            TameworkHappinessComponent happiness = component(
                    payload, "happiness", TameworkHappinessComponent.class);
            TameworkNeedsComponent needs = component(payload, "needs", TameworkNeedsComponent.class);
            TameworkBreedingComponent breeding = component(
                    payload, "breeding", TameworkBreedingComponent.class);
            TameworkLevelingComponent leveling = component(
                    payload, "leveling", TameworkLevelingComponent.class);
            TameworkTraitsComponent traits = component(payload, "traits", TameworkTraitsComponent.class);
            TameworkTalentsComponent talents = component(payload, "talents", TameworkTalentsComponent.class);
            TameworkLifeStageComponent lifeStage = component(
                    payload, "lifeStage", TameworkLifeStageComponent.class);
            TameworkAttachmentsComponent attachments = component(
                    payload, "attachments", TameworkAttachmentsComponent.class);
            Double currentHealth = optionalFiniteDouble(payload, "currentHealth");
            Double maximumHealth = optionalFiniteDouble(payload, "maximumHealth");
            if (!isHealthPairValid(currentHealth, maximumHealth)) {
                throw failure(Failure.INVALID_FIELD, "currentHealth",
                        "currentHealth and maximumHealth must be a valid pair");
            }
            Double healthPercent = optionalFiniteDouble(payload, "healthPercent");
            return DecodeResult.found(new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
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
                    currentHealth,
                    maximumHealth,
                    healthPercent,
                    capturedAtMs
            ));
        } catch (DecodeFailure ex) {
            return DecodeResult.failed(ex.failure, ex.field, ex.getMessage());
        }
    }

    /** Creates a deep, timestamp-preserving copy through the strict versioned boundary. */
    @Nonnull
    public CoopResidentStateSnapshotService.CoopResidentStateSnapshot copy(
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        DecodeResult result = decode(encode(snapshot));
        if (result.status() != Status.FOUND || result.snapshot() == null) {
            throw new IllegalStateException("Encoded snapshot could not be decoded: " + result.failure());
        }
        return result.snapshot();
    }

    /**
     * Builds a complete later snapshot while retaining prior optional state
     * that the live capture could not observe. This is a source-neutral store
     * boundary; it does not alter coop-ledger behavior.
     */
    @Nonnull
    public CoopResidentStateSnapshotService.CoopResidentStateSnapshot
            mergePreservingExisting(
                    @Nonnull CoopResidentStateSnapshotService
                            .CoopResidentStateSnapshot existing,
                    @Nonnull CoopResidentStateSnapshotService
                            .CoopResidentStateSnapshot captured
            ) {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot merged =
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        captured.npcUuid(),
                        prefer(captured.coopId(), existing.coopId()),
                        captured.residentSlot() >= 0
                                ? captured.residentSlot()
                                : existing.residentSlot(),
                        prefer(captured.roleId(), existing.roleId()),
                        prefer(captured.commandLinks(), existing.commandLinks()),
                        prefer(captured.owner(), existing.owner()),
                        prefer(captured.tamed(), existing.tamed()),
                        prefer(captured.npcName(), existing.npcName()),
                        prefer(captured.happiness(), existing.happiness()),
                        prefer(captured.needs(), existing.needs()),
                        prefer(captured.breeding(), existing.breeding()),
                        prefer(captured.leveling(), existing.leveling()),
                        prefer(captured.traits(), existing.traits()),
                        prefer(captured.talents(), existing.talents()),
                        prefer(captured.lifeStage(), existing.lifeStage()),
                        prefer(captured.attachments(), existing.attachments()),
                        preferredCurrentHealth(existing, captured),
                        preferredMaximumHealth(existing, captured),
                        prefer(captured.healthPercent(), existing.healthPercent()),
                        captured.capturedAtMs()
                );
        return copy(merged);
    }

    private static <T> T prefer(@Nullable T captured, @Nullable T existing) {
        return captured != null ? captured : existing;
    }

    @Nullable
    private static Double preferredCurrentHealth(
            CoopResidentStateSnapshotService.CoopResidentStateSnapshot existing,
            CoopResidentStateSnapshotService.CoopResidentStateSnapshot captured) {
        return captured.currentHealth() != null && captured.maximumHealth() != null
                ? captured.currentHealth() : existing.currentHealth();
    }

    @Nullable
    private static Double preferredMaximumHealth(
            CoopResidentStateSnapshotService.CoopResidentStateSnapshot existing,
            CoopResidentStateSnapshotService.CoopResidentStateSnapshot captured) {
        return captured.currentHealth() != null && captured.maximumHealth() != null
                ? captured.maximumHealth() : existing.maximumHealth();
    }

    private static void validateHealthPair(@Nullable Double currentHealth,
                                           @Nullable Double maximumHealth) {
        if (!isHealthPairValid(currentHealth, maximumHealth)) {
            throw new IllegalArgumentException("Snapshot health pair is invalid");
        }
    }

    private static boolean isHealthPairValid(@Nullable Double currentHealth,
                                             @Nullable Double maximumHealth) {
        return (currentHealth == null && maximumHealth == null)
                || (currentHealth != null && maximumHealth != null
                && Double.isFinite(currentHealth)
                && Double.isFinite(maximumHealth)
                && maximumHealth > 0.0D && currentHealth >= 0.0D
                && currentHealth <= maximumHealth);
    }

    private <T> void putComponent(@Nonnull JsonObject payload,
                                  @Nonnull String field,
                                  @Nullable T component,
                                  @Nonnull Class<T> type) {
        if (component == null) {
            return;
        }
        JsonElement value = gson.toJsonTree(component, type);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Snapshot component is not a JSON object: " + field);
        }
        payload.add(field, value.getAsJsonObject());
    }

    @Nullable
    private <T> T component(@Nonnull JsonObject payload,
                            @Nonnull String field,
                            @Nonnull Class<T> type) throws DecodeFailure {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            return null;
        }
        JsonElement value = payload.get(field);
        if (!value.isJsonObject()) {
            throw failure(Failure.INVALID_COMPONENT_DATA, field, "Component must be a JSON object");
        }
        try {
            T component = gson.fromJson(value, type);
            if (component == null) {
                throw failure(Failure.INVALID_COMPONENT_DATA, field, "Component decoded to null");
            }
            return component;
        } catch (DecodeFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(Failure.INVALID_COMPONENT_DATA, field, ex.getClass().getSimpleName());
        }
    }

    @Nonnull
    private UUID requiredSourceUuid(@Nonnull JsonObject payload) throws DecodeFailure {
        String raw = optionalString(payload, "npcUuid");
        if (raw == null) {
            throw failure(Failure.MISSING_SOURCE_UUID, "npcUuid", "Source NPC UUID is required");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw failure(Failure.INVALID_SOURCE_UUID, "npcUuid", raw);
        }
    }

    @Nullable
    private String optionalString(@Nonnull JsonObject payload, @Nonnull String field) throws DecodeFailure {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            return null;
        }
        JsonElement value = payload.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw failure(Failure.INVALID_FIELD, field, "Expected a JSON string");
        }
        try {
            String decoded = value.getAsString();
            return decoded == null || decoded.isBlank() ? null : decoded;
        } catch (RuntimeException ex) {
            throw failure(Failure.INVALID_FIELD, field, ex.getClass().getSimpleName());
        }
    }

    private int optionalInt(@Nonnull JsonObject payload,
                            @Nonnull String field,
                            int fallback) throws DecodeFailure {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement value = payload.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw failure(Failure.INVALID_FIELD, field, "Expected a JSON integer");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.intValueExact();
        } catch (RuntimeException ex) {
            throw failure(Failure.INVALID_FIELD, field, ex.getClass().getSimpleName());
        }
    }

    private long optionalLong(@Nonnull JsonObject payload,
                              @Nonnull String field,
                              long fallback) throws DecodeFailure {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement value = payload.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw failure(Failure.INVALID_FIELD, field, "Expected a JSON long");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.longValueExact();
        } catch (RuntimeException ex) {
            throw failure(Failure.INVALID_FIELD, field, ex.getClass().getSimpleName());
        }
    }

    @Nullable
    private Double optionalFiniteDouble(@Nonnull JsonObject payload,
                                        @Nonnull String field) throws DecodeFailure {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            return null;
        }
        JsonElement valueElement = payload.get(field);
        if (!valueElement.isJsonPrimitive() || !valueElement.getAsJsonPrimitive().isNumber()) {
            throw failure(Failure.INVALID_FIELD, field, "Expected a JSON number");
        }
        try {
            double value = valueElement.getAsDouble();
            if (!Double.isFinite(value)) {
                throw failure(Failure.INVALID_FIELD, field, "Expected a finite number");
            }
            return value;
        } catch (DecodeFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(Failure.INVALID_FIELD, field, ex.getClass().getSimpleName());
        }
    }

    @Nullable
    private String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String normalizeRoleId(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private DecodeFailure failure(@Nonnull Failure failure,
                                  @Nullable String field,
                                  @Nullable String detail) {
        return new DecodeFailure(failure, field, detail);
    }

    /** Outcome category for strict snapshot decoding. */
    public enum Status {
        FOUND,
        NOT_FOUND,
        FAILED
    }

    /** Machine-readable reason for a failed snapshot decode. */
    public enum Failure {
        INVALID_JSON,
        INVALID_ROOT,
        UNSUPPORTED_VERSION,
        MISSING_SOURCE_UUID,
        INVALID_SOURCE_UUID,
        INVALID_FIELD,
        INVALID_COMPONENT_DATA
    }

    /** Immutable strict decode outcome. */
    public record DecodeResult(@Nonnull Status status,
                               @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                               @Nullable Failure failure,
                               @Nullable String field,
                               @Nullable String detail) {
        @Nonnull
        static DecodeResult found(@Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
            return new DecodeResult(Status.FOUND, snapshot, null, null, null);
        }

        @Nonnull
        static DecodeResult notFound() {
            return new DecodeResult(Status.NOT_FOUND, null, null, null, null);
        }

        @Nonnull
        static DecodeResult failed(@Nonnull Failure failure,
                                   @Nullable String field,
                                   @Nullable String detail) {
            return new DecodeResult(Status.FAILED, null, failure, field, detail);
        }

        @Nullable
        public CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshotOrNull() {
            return status == Status.FOUND ? snapshot : null;
        }
    }

    private static final class DecodeFailure extends Exception {
        private final Failure failure;
        private final String field;

        private DecodeFailure(@Nonnull Failure failure,
                              @Nullable String field,
                              @Nullable String detail) {
            super(detail);
            this.failure = failure;
            this.field = field;
        }
    }
}
