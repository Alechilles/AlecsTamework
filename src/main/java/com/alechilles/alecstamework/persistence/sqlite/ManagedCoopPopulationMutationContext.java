package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationReleaseCommitRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Encodes the exact schema-v5 managed-coop mutation committed with a population journal entry.
 *
 * <p>The returned JSON is an extension object intended to be merged into an owner-population
 * target context. Parsing is deliberately strict so corrupt or stale recovery evidence fails the
 * enclosing SQLite transaction instead of falling back to a second occupancy authority.</p>
 */
public final class ManagedCoopPopulationMutationContext {
    static final String FIELD = "managedCoopMutation";
    private static final int VERSION = 1;

    private ManagedCoopPopulationMutationContext() {
    }

    /** Builds a journal extension carrying the complete deterministic v5 capture claim. */
    @Nonnull
    public static String captureExtensionJson(@Nonnull CaptureRequest request) {
        Objects.requireNonNull(request, "request");
        JsonObject mutation = base(Mode.CAPTURE);
        writeCommon(
                mutation,
                request.operationId(),
                request.residentId(),
                request.authorityKey(),
                request.coopId(),
                request.residentSlot(),
                request.profileId(),
                request.snapshotHash(),
                request.expectedResidentGeneration(),
                request.nowMs()
        );
        addNullableString(mutation, "roleId", request.roleId());
        mutation.addProperty("sourceNpcUuid", request.sourceNpcUuid().toString());
        addNullableString(mutation, "snapshotJson", request.snapshotJson());
        mutation.addProperty("snapshotVersion", request.snapshotVersion());
        return extension(mutation).toString();
    }

    /** Builds a journal extension for one exact already-claimed v5 release projection. */
    @Nonnull
    public static String releaseExtensionJson(@Nonnull PopulationReleaseCommitRequest request) {
        Objects.requireNonNull(request, "request");
        JsonObject mutation = base(Mode.RELEASE);
        writeCommon(
                mutation,
                request.operationId(),
                request.residentId(),
                request.authorityKey(),
                request.coopId(),
                request.residentSlot(),
                request.profileId(),
                request.snapshotHash(),
                request.expectedResidentGeneration(),
                request.nowMs()
        );
        mutation.addProperty("plannedTargetUuid", request.plannedTargetUuid().toString());
        mutation.addProperty("actualTargetUuid", request.actualTargetUuid().toString());
        mutation.addProperty("expectedOperationGeneration", request.expectedOperationGeneration());
        return extension(mutation).toString();
    }

    @Nullable
    static ParsedMutation parse(@Nullable String targetContextJson) {
        if (targetContextJson == null || targetContextJson.isBlank()) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(targetContextJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Population target context must be an object.");
        }
        JsonElement rawMutation = parsed.getAsJsonObject().get(FIELD);
        if (rawMutation == null || rawMutation.isJsonNull()) {
            return null;
        }
        if (!rawMutation.isJsonObject()) {
            throw new IllegalArgumentException("Managed-coop population mutation must be an object.");
        }
        JsonObject mutation = rawMutation.getAsJsonObject();
        if (requiredInt(mutation, "version") != VERSION) {
            throw new IllegalArgumentException("Unsupported managed-coop population mutation version.");
        }
        Mode mode = Mode.valueOf(requiredString(mutation, "mode").toUpperCase(Locale.ROOT));
        return switch (mode) {
            case CAPTURE -> new ParsedMutation(capture(mutation), null);
            case RELEASE -> new ParsedMutation(null, release(mutation));
        };
    }

    @Nonnull
    private static CaptureRequest capture(@Nonnull JsonObject source) {
        Common common = common(source);
        return new CaptureRequest(
                common.operationId(),
                common.residentId(),
                common.authorityKey(),
                common.coopId(),
                common.residentSlot(),
                common.profileId(),
                nullableString(source, "roleId"),
                requiredUuid(source, "sourceNpcUuid"),
                nullableString(source, "snapshotJson"),
                common.snapshotHash(),
                requiredInt(source, "snapshotVersion"),
                common.expectedResidentGeneration(),
                common.nowMs()
        );
    }

    @Nonnull
    private static PopulationReleaseCommitRequest release(@Nonnull JsonObject source) {
        Common common = common(source);
        return new PopulationReleaseCommitRequest(
                common.operationId(),
                common.residentId(),
                common.authorityKey(),
                common.coopId(),
                common.residentSlot(),
                common.profileId(),
                requiredUuid(source, "plannedTargetUuid"),
                requiredUuid(source, "actualTargetUuid"),
                common.snapshotHash(),
                common.expectedResidentGeneration(),
                requiredLong(source, "expectedOperationGeneration"),
                common.nowMs()
        );
    }

    @Nonnull
    private static Common common(@Nonnull JsonObject source) {
        return new Common(
                requiredString(source, "operationId"),
                requiredString(source, "residentId"),
                new ManagedCoopAuthorityKey(
                        requiredString(source, "worldName"),
                        requiredInt(source, "x"),
                        requiredInt(source, "y"),
                        requiredInt(source, "z")
                ),
                requiredString(source, "coopId"),
                requiredInt(source, "residentSlot"),
                requiredString(source, "profileId"),
                nullableString(source, "snapshotHash"),
                requiredLong(source, "expectedResidentGeneration"),
                requiredLong(source, "nowMs")
        );
    }

    @Nonnull
    private static JsonObject base(@Nonnull Mode mode) {
        JsonObject mutation = new JsonObject();
        mutation.addProperty("version", VERSION);
        mutation.addProperty("mode", mode.name());
        return mutation;
    }

    private static void writeCommon(@Nonnull JsonObject target,
                                    @Nonnull String operationId,
                                    @Nonnull String residentId,
                                    @Nonnull ManagedCoopAuthorityKey authorityKey,
                                    @Nonnull String coopId,
                                    int residentSlot,
                                    @Nonnull String profileId,
                                    @Nullable String snapshotHash,
                                    long expectedResidentGeneration,
                                    long nowMs) {
        target.addProperty("operationId", operationId);
        target.addProperty("residentId", residentId);
        target.addProperty("worldName", authorityKey.worldName());
        target.addProperty("x", authorityKey.x());
        target.addProperty("y", authorityKey.y());
        target.addProperty("z", authorityKey.z());
        target.addProperty("coopId", coopId);
        target.addProperty("residentSlot", residentSlot);
        target.addProperty("profileId", profileId);
        addNullableString(target, "snapshotHash", snapshotHash);
        target.addProperty("expectedResidentGeneration", expectedResidentGeneration);
        target.addProperty("nowMs", nowMs);
    }

    @Nonnull
    private static JsonObject extension(@Nonnull JsonObject mutation) {
        JsonObject extension = new JsonObject();
        extension.add(FIELD, mutation);
        return extension;
    }

    private static void addNullableString(@Nonnull JsonObject target,
                                          @Nonnull String field,
                                          @Nullable String value) {
        if (value == null) {
            target.add(field, null);
        } else {
            target.addProperty(field, value);
        }
    }

    @Nonnull
    private static String requiredString(@Nonnull JsonObject source, @Nonnull String field) {
        String value = nullableString(source, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing managed-coop mutation field: " + field);
        }
        return value.trim();
    }

    @Nullable
    private static String nullableString(@Nonnull JsonObject source, @Nonnull String field) {
        JsonElement value = source.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int requiredInt(@Nonnull JsonObject source, @Nonnull String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing managed-coop mutation field: " + field);
        }
        return value.getAsInt();
    }

    private static long requiredLong(@Nonnull JsonObject source, @Nonnull String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing managed-coop mutation field: " + field);
        }
        return value.getAsLong();
    }

    @Nonnull
    private static UUID requiredUuid(@Nonnull JsonObject source, @Nonnull String field) {
        return UUID.fromString(requiredString(source, field));
    }

    enum Mode {
        CAPTURE,
        RELEASE
    }

    record ParsedMutation(@Nullable CaptureRequest capture,
                          @Nullable PopulationReleaseCommitRequest release) {
    }

    private record Common(@Nonnull String operationId,
                          @Nonnull String residentId,
                          @Nonnull ManagedCoopAuthorityKey authorityKey,
                          @Nonnull String coopId,
                          int residentSlot,
                          @Nonnull String profileId,
                          @Nullable String snapshotHash,
                          long expectedResidentGeneration,
                          long nowMs) {
    }
}
