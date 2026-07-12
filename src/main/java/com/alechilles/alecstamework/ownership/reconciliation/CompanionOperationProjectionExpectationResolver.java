package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.LoadedNpcObservation;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProjectionKey;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves and validates restart-visible spawn markers for population recovery. */
final class CompanionOperationProjectionExpectationResolver {
    private static final String MANAGED_COOP_MUTATION = "managedCoopMutation";

    private CompanionOperationProjectionExpectationResolver() {
    }

    /**
     * Returns a physical observation only when one exact marker agrees with its journal target.
     * Alternate identities, duplicate markers, and incomplete owner/location evidence quarantine
     * the operation rather than allowing recovery to infer absence.
     */
    @Nonnull
    static Resolution resolve(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull CompanionPopulationEvidenceSet evidenceSet
    ) {
        return resolve(
                operation,
                evidenceSet,
                new LoadedNpcIdentitySnapshot(0L, true, List.of())
        );
    }

    @Nonnull
    static Resolution resolve(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nonnull LoadedNpcIdentitySnapshot loadedIdentities
    ) {
        final ExpectedProjection expected;
        try {
            expected = expectedProjection(operation);
        } catch (RuntimeException malformed) {
            return Resolution.ambiguous("operation-recovery-projection-metadata-invalid");
        }
        if (expected == null) {
            return Resolution.ordinary();
        }
        List<CompanionPopulationEvidenceSet.ProjectionObservation> observations =
                evidenceSet.projectionObservations(expected.fingerprint());
        String liveAmbiguity = liveAmbiguity(
                expected, Objects.requireNonNull(loadedIdentities, "loadedIdentities"),
                !observations.isEmpty()
        );
        if (liveAmbiguity != null) {
            return Resolution.ambiguous(liveAmbiguity);
        }
        if (observations.isEmpty()) {
            return Resolution.ordinary();
        }
        if (observations.size() != 1) {
            return Resolution.ambiguous("operation-recovery-projection-evidence-duplicated");
        }
        CompanionPopulationEvidenceSet.ProjectionObservation observation = observations.getFirst();
        if (!expected.plannedNpcUuid().equals(observation.componentUuid())
                || !expected.plannedNpcUuid().equals(observation.legacyNpcUuid())) {
            return Resolution.ambiguous(
                    "operation-recovery-projection-evidence-identity-mismatch");
        }
        return validateExact(operation, evidenceSet, expected, observation);
    }

    /**
     * Treats every loaded planned identity or exact marker as positive/conflicting evidence.
     * Loaded observations intentionally cannot synthesize physical persisted state because the
     * index does not retain owner or chunk coordinates; they only prevent an unsafe absence
     * decision until saved evidence catches up.
     */
    @Nullable
    private static String liveAmbiguity(
            @Nonnull ExpectedProjection expected,
            @Nonnull LoadedNpcIdentitySnapshot loadedIdentities,
            boolean persistedMarkerPresent
    ) {
        if (!loadedIdentities.initializationComplete()) {
            return "operation-recovery-loaded-identity-incomplete";
        }
        List<LoadedNpcObservation> relevant = loadedIdentities.observations().stream()
                .filter(observation -> expected.projectionKey().equals(observation.projectionKey())
                        || expected.plannedNpcUuid().equals(observation.componentUuid())
                        || expected.plannedNpcUuid().equals(observation.legacyNpcUuid()))
                .toList();
        if (relevant.isEmpty()) {
            return null;
        }
        if (relevant.size() != 1) {
            return "operation-recovery-projection-live-evidence-duplicated";
        }
        LoadedNpcObservation observation = relevant.getFirst();
        if (!expected.projectionKey().equals(observation.projectionKey())) {
            return "operation-recovery-projection-live-marker-mismatch";
        }
        if (!expected.plannedNpcUuid().equals(observation.componentUuid())
                || !expected.plannedNpcUuid().equals(observation.legacyNpcUuid())) {
            return "operation-recovery-projection-live-identity-mismatch";
        }
        if (!expected.targetLocation().worldName().equals(observation.location().worldName())) {
            return "operation-recovery-projection-live-world-mismatch";
        }
        return persistedMarkerPresent
                ? null
                : "operation-recovery-projection-live-evidence-not-persisted";
    }

    @Nonnull
    private static Resolution validateExact(
            CompanionPopulationOperationRecord operation,
            CompanionPopulationEvidenceSet evidenceSet,
            ExpectedProjection expected,
            CompanionPopulationEvidenceSet.ProjectionObservation observation
    ) {
        CompanionPopulationEvidence marker = observation.evidence();
        final UUID targetOwner;
        try {
            targetOwner = parseOwner(parseObject(operation.newStateJson()));
        } catch (RuntimeException malformed) {
            return Resolution.ambiguous("operation-recovery-projection-owner-metadata-invalid");
        }
        if (!expected.plannedNpcUuid().equals(marker.npcUuid())
                || !marker.ownerObserved()
                || !Objects.equals(targetOwner, marker.ownerUuid())) {
            return Resolution.ambiguous("operation-recovery-projection-owner-mismatch");
        }
        CompanionPopulationEvidenceSet.PhysicalLocation markerLocation = markerLocation(marker);
        if (markerLocation == null || !expected.targetLocation().equals(markerLocation)) {
            return Resolution.ambiguous("operation-recovery-projection-location-mismatch");
        }
        if (hasOrdinaryConflict(evidenceSet, expected.plannedNpcUuid())) {
            return Resolution.ambiguous("operation-recovery-projection-ordinary-evidence-conflict");
        }
        CompanionPopulationEvidenceSet.ResolvedEvidence ordinary =
                evidenceSet.byNpcUuid().get(expected.plannedNpcUuid());
        if (ordinary != null) {
            return ordinaryAgrees(
                    ordinary, marker, markerLocation, observation.deathObserved())
                    ? Resolution.exact(ordinary)
                    : Resolution.ambiguous(
                            "operation-recovery-projection-ordinary-evidence-mismatch");
        }
        return Resolution.exact(new CompanionPopulationEvidenceSet.ResolvedEvidence(
                expected.plannedNpcUuid(),
                marker.ownerUuid(),
                true,
                true,
                observation.deathObserved(),
                observation.deathObserved()
                        ? CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY
                        : CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                markerLocation.worldName(),
                markerLocation,
                1,
                Set.of(marker.source())
        ));
    }

    private static boolean ordinaryAgrees(
            CompanionPopulationEvidenceSet.ResolvedEvidence ordinary,
            CompanionPopulationEvidence marker,
            CompanionPopulationEvidenceSet.PhysicalLocation markerLocation,
            boolean deathObserved
    ) {
        return ordinary.physical()
                && ordinary.deathObserved() == deathObserved
                && ordinary.ownerObserved()
                && Objects.equals(ordinary.observedOwnerUuid(), marker.ownerUuid())
                && Objects.equals(ordinary.physicalLocation(), markerLocation);
    }

    private static boolean hasOrdinaryConflict(
            CompanionPopulationEvidenceSet evidenceSet,
            UUID plannedNpcUuid
    ) {
        for (CompanionPopulationEvidenceSet.Conflict conflict : evidenceSet.conflicts()) {
            if (plannedNpcUuid.equals(conflict.npcUuid())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static CompanionPopulationEvidenceSet.PhysicalLocation markerLocation(
            CompanionPopulationEvidence marker
    ) {
        return marker.physicalWorldName() == null
                || marker.physicalChunkX() == null
                || marker.physicalChunkZ() == null
                ? null
                : new CompanionPopulationEvidenceSet.PhysicalLocation(
                        marker.physicalWorldName(),
                        marker.physicalChunkX(),
                        marker.physicalChunkZ()
                );
    }

    @Nullable
    private static ExpectedProjection expectedProjection(
            @Nonnull CompanionPopulationOperationRecord operation
    ) {
        Objects.requireNonNull(operation, "operation");
        if (OwnerPopulationOperation.BREEDING.name().equalsIgnoreCase(operation.operationType())) {
            return breeding(operation, parseObject(operation.targetContextJson()));
        }
        JsonObject context = optionalManagedContext(operation.targetContextJson());
        if (context == null || !context.has(MANAGED_COOP_MUTATION)) {
            return null;
        }
        JsonElement mutationElement = context.get(MANAGED_COOP_MUTATION);
        if (mutationElement == null || mutationElement.isJsonNull()
                || !mutationElement.isJsonObject()) {
            throw new IllegalArgumentException("Managed-coop mutation must be an object.");
        }
        JsonObject mutation = mutationElement.getAsJsonObject();
        String mode = requiredText(mutation, "mode").toUpperCase(Locale.ROOT);
        if ("CAPTURE".equals(mode)) {
            return null;
        }
        if (!OwnerPopulationOperation.RESTORE.name().equalsIgnoreCase(operation.operationType())
                || !"RELEASE".equals(mode)
                || !"coop_release".equalsIgnoreCase(requiredText(context, "operation"))) {
            throw new IllegalArgumentException("Managed-coop projection mode is invalid.");
        }
        return managedCoopRelease(operation, context, mutation);
    }

    @Nullable
    private static JsonObject optionalManagedContext(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return parseObject(json);
        } catch (RuntimeException malformed) {
            if (json.contains(MANAGED_COOP_MUTATION)) {
                throw malformed;
            }
            return null;
        }
    }

    @Nonnull
    private static ExpectedProjection breeding(
            CompanionPopulationOperationRecord operation,
            JsonObject context
    ) {
        UUID plannedNpcUuid = requiredUuid(context, "plannedNpcUuid");
        return expected(
                operation.profileId(),
                requiredText(context, "idempotencyKey"),
                TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                requiredText(context, "childKey"),
                plannedNpcUuid,
                1L,
                plannedNpcUuid,
                targetLocation(context)
        );
    }

    @Nonnull
    private static ExpectedProjection managedCoopRelease(
            CompanionPopulationOperationRecord operation,
            JsonObject context,
            JsonObject mutation
    ) {
        UUID previousNpcUuid = requiredUuid(context, "previousNpcUuid");
        UUID plannedNpcUuid = requiredUuid(context, "plannedNpcUuid");
        if (previousNpcUuid.equals(plannedNpcUuid)) {
            throw new IllegalArgumentException("Managed-coop release UUIDs must differ.");
        }
        ManagedCoopAuthorityKey authority = new ManagedCoopAuthorityKey(
                requiredText(mutation, "worldName"),
                requiredInt(mutation, "x"),
                requiredInt(mutation, "y"),
                requiredInt(mutation, "z")
        );
        long generation = requiredLong(mutation, "expectedOperationGeneration");
        if (generation < 1L) {
            throw new IllegalArgumentException("Managed-coop release generation must be positive.");
        }
        return expected(
                operation.profileId(),
                requiredText(mutation, "operationId"),
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                authority.slotKey(requiredInt(mutation, "residentSlot")),
                previousNpcUuid,
                generation,
                plannedNpcUuid,
                targetLocation(context)
        );
    }

    @Nonnull
    private static ExpectedProjection expected(
            String profileId,
            String operationId,
            String kind,
            String slotKey,
            UUID sourceNpcUuid,
            long generation,
            UUID plannedNpcUuid,
            CompanionPopulationEvidenceSet.PhysicalLocation targetLocation
    ) {
        ProjectionKey projectionKey = new ProjectionKey(
                profileId, operationId, kind, slotKey, sourceNpcUuid, generation
        );
        return new ExpectedProjection(
                CompanionProjectionEvidence.fingerprint(
                        profileId, operationId, kind, slotKey, sourceNpcUuid, generation
                ),
                plannedNpcUuid,
                targetLocation,
                projectionKey
        );
    }

    @Nonnull
    private static CompanionPopulationEvidenceSet.PhysicalLocation targetLocation(
            JsonObject context
    ) {
        return new CompanionPopulationEvidenceSet.PhysicalLocation(
                requiredText(context, "world"),
                requiredInt(context, "chunkX"),
                requiredInt(context, "chunkZ")
        );
    }

    @Nullable
    static UUID parseOwner(JsonObject object) {
        if (!object.has("ownerUuid") && !object.has("owner")) {
            throw new IllegalArgumentException("Missing population operation owner state.");
        }
        JsonElement value = object.has("ownerUuid") ? object.get("ownerUuid") : object.get("owner");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        String raw = value.getAsString();
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    @Nonnull
    static JsonObject parseObject(@Nullable String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Population operation JSON is missing.");
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Population operation JSON must be an object.");
        }
        return parsed.getAsJsonObject();
    }

    @Nullable
    static String nullableString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        String raw = value.getAsString();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    static boolean booleanValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null) {
            return false;
        }
        if (value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid operation context boolean: " + field);
        }
        return value.getAsBoolean();
    }

    @Nullable
    static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    @Nonnull
    private static String requiredText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing projection field: " + field);
        }
        String normalized = value.getAsString().trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Blank projection field: " + field);
        }
        return normalized;
    }

    @Nonnull
    private static UUID requiredUuid(JsonObject object, String field) {
        return UUID.fromString(requiredText(object, field));
    }

    private static int requiredInt(JsonObject object, String field) {
        return requiredNumber(object, field).getAsInt();
    }

    private static long requiredLong(JsonObject object, String field) {
        return requiredNumber(object, field).getAsLong();
    }

    @Nonnull
    private static JsonElement requiredNumber(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing projection number: " + field);
        }
        return value;
    }

    record Resolution(@Nullable CompanionPopulationEvidenceSet.ResolvedEvidence exactEvidence,
                      @Nullable String ambiguityReason) {
        static Resolution ordinary() {
            return new Resolution(null, null);
        }

        static Resolution exact(CompanionPopulationEvidenceSet.ResolvedEvidence evidence) {
            return new Resolution(Objects.requireNonNull(evidence, "evidence"), null);
        }

        static Resolution ambiguous(String reason) {
            return new Resolution(null, Objects.requireNonNull(reason, "reason"));
        }
    }

    private record ExpectedProjection(
            @Nonnull String fingerprint,
            @Nonnull UUID plannedNpcUuid,
            @Nonnull CompanionPopulationEvidenceSet.PhysicalLocation targetLocation,
            @Nonnull ProjectionKey projectionKey
    ) {
    }
}
