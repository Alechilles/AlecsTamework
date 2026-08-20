package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable recovery payload for one admitted breeding litter. */
public record BreedingLitterOperation(
        @Nonnull UUID litterId,
        @Nonnull Parent parentA,
        @Nonnull Parent parentB,
        @Nonnull String worldName,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        float spawnPitch,
        float spawnRoll,
        @Nullable String breedingConfigId,
        double parentAFertility,
        double parentBFertility,
        double expectedOffspring,
        int requestedCount,
        @Nonnull List<ChildPlan> children,
        @Nonnull PopulationAdmissionToken admissionToken,
        long requestedAtMs
) {
    public static final OperationKind KIND =
            new OperationKind("breeding_litter");
    public static final Definition DEFINITION = new Definition();

    /** Returns the journal ID for the durable litter job linked to this litter. */
    @Nonnull
    public static UUID jobOperationId(@Nonnull UUID litterId) {
        return UUID.nameUUIDFromBytes(
                (Objects.requireNonNull(litterId, "litterId")
                        + ":breeding-litter-job")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    public BreedingLitterOperation {
        litterId = Objects.requireNonNull(litterId, "litterId");
        parentA = Objects.requireNonNull(parentA, "parentA");
        parentB = Objects.requireNonNull(parentB, "parentB");
        worldName = requireText(worldName, "worldName");
        breedingConfigId = normalize(breedingConfigId);
        admissionToken = Objects.requireNonNull(
                admissionToken, "admissionToken"
        );
        if (compare(parentA.uuid(), parentB.uuid()) >= 0) {
            throw new IllegalArgumentException(
                    "Breeding litter parents must be unique and sorted"
            );
        }
        if (!Double.isFinite(spawnX) || !Double.isFinite(spawnY)
                || !Double.isFinite(spawnZ)
                || !Float.isFinite(spawnYaw)
                || !Float.isFinite(spawnPitch)
                || !Float.isFinite(spawnRoll)
                || !Double.isFinite(parentAFertility)
                || !Double.isFinite(parentBFertility)
                || !Double.isFinite(expectedOffspring)
                || parentAFertility < 0.0 || parentBFertility < 0.0
                || expectedOffspring < 0.0 || requestedCount <= 0) {
            throw new IllegalArgumentException(
                    "Breeding litter frozen values are invalid"
            );
        }
        if (children == null || children.size() != requestedCount) {
            throw new IllegalArgumentException(
                    "One planned child is required per litter ordinal"
            );
        }
        for (int ordinal = 0; ordinal < requestedCount; ordinal++) {
            if (!plannedChildId(litterId, ordinal).equals(
                    children.get(ordinal).uuid()
            )) {
                throw new IllegalArgumentException(
                        "Breeding litter planned child IDs are not deterministic"
                );
            }
        }
        if (!litterId.equals(admissionToken.operationId())) {
            throw new IllegalArgumentException(
                    "Breeding litter token must belong to the litter"
            );
        }
        children = List.copyOf(children);
    }

    /** Creates deterministic actual Hytale UUIDs before any live spawn. */
    @Nonnull
    public static List<UUID> plannedChildIds(
            @Nonnull UUID litterId,
            int count
    ) {
        if (litterId == null || count <= 0) {
            throw new IllegalArgumentException(
                    "A litter ID and positive child count are required"
            );
        }
        java.util.ArrayList<UUID> values = new java.util.ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            values.add(plannedChildId(litterId, ordinal));
        }
        return List.copyOf(values);
    }

    /** Returns planned actual UUIDs in ordinal order. */
    @Nonnull
    public List<UUID> plannedChildIds() {
        return children.stream().map(ChildPlan::uuid).toList();
    }

    /** Encodes exact live child receipts for durable operation evidence. */
    @Nonnull
    public String encodeReceipts(@Nonnull Map<Integer, UUID> receipts) {
        validateReceipts(receipts);
        JsonObject root = new JsonObject();
        JsonObject values = new JsonObject();
        receipts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.addProperty(
                        Integer.toString(entry.getKey()),
                        entry.getValue().toString()
                ));
        root.add("children", values);
        return root.toString();
    }

    /** Decodes and validates exact live child receipts. */
    @Nonnull
    public Map<Integer, UUID> decodeReceipts(@Nonnull String evidence) {
        JsonObject values = JsonParser.parseString(evidence)
                .getAsJsonObject().getAsJsonObject("children");
        LinkedHashMap<Integer, UUID> receipts = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : values.entrySet()) {
            receipts.put(
                    Integer.parseInt(entry.getKey()),
                    UUID.fromString(entry.getValue().getAsString())
            );
        }
        validateReceipts(receipts);
        return Map.copyOf(receipts);
    }

    private void validateReceipts(Map<Integer, UUID> receipts) {
        if (receipts == null || receipts.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey() < 0
                        || entry.getKey() >= requestedCount
                        || entry.getValue() == null
                        || !entry.getValue().equals(
                        children.get(entry.getKey()).uuid()
                ))) {
            throw new IllegalArgumentException(
                    "Breeding litter receipts must match planned ordinals"
            );
        }
    }

    private static UUID plannedChildId(UUID litterId, int ordinal) {
        return UUID.nameUUIDFromBytes(
                (litterId + ":actual-child:" + ordinal)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static int compare(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Frozen parent evidence used by recovery and inheritance. */
    public record Parent(
            @Nonnull UUID uuid,
            @Nonnull String roleId,
            int roleIndex,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            boolean tamed
    ) {
        public Parent {
            uuid = Objects.requireNonNull(uuid, "uuid");
            roleId = requireText(roleId, "roleId");
            ownerName = normalize(ownerName);
        }
    }

    /** Frozen child selection used for exact replay after restart. */
    public record ChildPlan(
            @Nonnull UUID uuid,
            @Nonnull String roleId,
            @Nullable String adultRoleId,
            @Nullable String gender,
            @Nullable String lifecycleFamilyId,
            @Nullable String lifecycleLineId
    ) {
        public ChildPlan {
            uuid = Objects.requireNonNull(uuid, "uuid");
            roleId = requireText(roleId, "roleId");
            adultRoleId = normalize(adultRoleId);
            gender = normalize(gender);
            lifecycleFamilyId = normalize(lifecycleFamilyId);
            lifecycleLineId = normalize(lifecycleLineId);
        }
    }

    /** Versioned codec for the shared operation envelope. */
    public static final class Definition
            implements OperationDefinition<BreedingLitterOperation> {
        private Definition() {
        }

        @Override
        public OperationKind kind() {
            return KIND;
        }

        @Override
        public int payloadVersion() {
            return 1;
        }

        @Override
        public Class<BreedingLitterOperation> payloadType() {
            return BreedingLitterOperation.class;
        }

        @Override
        public String encode(BreedingLitterOperation value) {
            JsonObject json = new JsonObject();
            json.addProperty("litterId", value.litterId().toString());
            json.add("parentA", parent(value.parentA()));
            json.add("parentB", parent(value.parentB()));
            json.addProperty("worldName", value.worldName());
            json.addProperty("spawnX", value.spawnX());
            json.addProperty("spawnY", value.spawnY());
            json.addProperty("spawnZ", value.spawnZ());
            json.addProperty("spawnYaw", value.spawnYaw());
            json.addProperty("spawnPitch", value.spawnPitch());
            json.addProperty("spawnRoll", value.spawnRoll());
            if (value.breedingConfigId() != null) {
                json.addProperty(
                        "breedingConfigId", value.breedingConfigId()
                );
            }
            json.addProperty("parentAFertility", value.parentAFertility());
            json.addProperty("parentBFertility", value.parentBFertility());
            json.addProperty("expectedOffspring", value.expectedOffspring());
            json.addProperty("requestedCount", value.requestedCount());
            JsonArray children = new JsonArray();
            value.children().forEach(child -> children.add(child(child)));
            json.add("children", children);
            json.add("admissionToken", token(value.admissionToken()));
            json.addProperty("requestedAtMs", value.requestedAtMs());
            return json.toString();
        }

        @Override
        public BreedingLitterOperation decode(String payloadJson) {
            JsonObject json = JsonParser.parseString(payloadJson)
                    .getAsJsonObject();
            JsonArray children = json.getAsJsonArray("children");
            java.util.ArrayList<ChildPlan> childPlans =
                    new java.util.ArrayList<>(children.size());
            children.forEach(child ->
                    childPlans.add(child(child.getAsJsonObject())));
            return new BreedingLitterOperation(
                    UUID.fromString(json.get("litterId").getAsString()),
                    parent(json.getAsJsonObject("parentA")),
                    parent(json.getAsJsonObject("parentB")),
                    json.get("worldName").getAsString(),
                    json.get("spawnX").getAsDouble(),
                    json.get("spawnY").getAsDouble(),
                    json.get("spawnZ").getAsDouble(),
                    json.get("spawnYaw").getAsFloat(),
                    json.get("spawnPitch").getAsFloat(),
                    json.get("spawnRoll").getAsFloat(),
                    json.has("breedingConfigId")
                            ? json.get("breedingConfigId").getAsString()
                            : null,
                    json.get("parentAFertility").getAsDouble(),
                    json.get("parentBFertility").getAsDouble(),
                    json.get("expectedOffspring").getAsDouble(),
                    json.get("requestedCount").getAsInt(),
                    childPlans,
                    token(json.getAsJsonObject("admissionToken")),
                    json.get("requestedAtMs").getAsLong()
            );
        }

        @Override
        public boolean allowsUnknownLiveReverification(
                @Nonnull com.alechilles.alecstamework.persistence.operation
                        .OperationEnvelope operation
        ) {
            return KIND.equals(operation.kind());
        }

        private static JsonObject parent(Parent value) {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", value.uuid().toString());
            json.addProperty("roleId", value.roleId());
            json.addProperty("roleIndex", value.roleIndex());
            if (value.ownerId() != null) {
                json.addProperty("ownerId", value.ownerId().toString());
            }
            if (value.ownerName() != null) {
                json.addProperty("ownerName", value.ownerName());
            }
            json.addProperty("tamed", value.tamed());
            return json;
        }

        private static Parent parent(JsonObject json) {
            return new Parent(
                    UUID.fromString(json.get("uuid").getAsString()),
                    json.get("roleId").getAsString(),
                    json.get("roleIndex").getAsInt(),
                    json.has("ownerId")
                            ? UUID.fromString(json.get("ownerId").getAsString())
                            : null,
                    json.has("ownerName")
                            ? json.get("ownerName").getAsString() : null,
                    json.get("tamed").getAsBoolean()
            );
        }

        private static JsonObject child(ChildPlan value) {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", value.uuid().toString());
            json.addProperty("roleId", value.roleId());
            add(json, "adultRoleId", value.adultRoleId());
            add(json, "gender", value.gender());
            add(json, "lifecycleFamilyId", value.lifecycleFamilyId());
            add(json, "lifecycleLineId", value.lifecycleLineId());
            return json;
        }

        private static ChildPlan child(JsonObject json) {
            return new ChildPlan(
                    UUID.fromString(json.get("uuid").getAsString()),
                    json.get("roleId").getAsString(),
                    text(json, "adultRoleId"),
                    text(json, "gender"),
                    text(json, "lifecycleFamilyId"),
                    text(json, "lifecycleLineId")
            );
        }

        private static void add(
                JsonObject json,
                String key,
                @Nullable String value
        ) {
            if (value != null) {
                json.addProperty(key, value);
            }
        }

        @Nullable
        private static String text(JsonObject json, String key) {
            return json.has(key) ? json.get(key).getAsString() : null;
        }

        private static JsonObject token(PopulationAdmissionToken value) {
            JsonObject json = new JsonObject();
            json.addProperty("operationId", value.operationId().toString());
            json.addProperty("reservationId", value.reservationId().toString());
            json.addProperty(
                    "expiresAtMonotonicNanos",
                    value.expiresAtMonotonicNanos()
            );
            json.addProperty("settingsRevision", value.settingsRevision());
            json.addProperty(
                    "providerGenerationToken",
                    value.providerGenerationToken()
            );
            json.addProperty("readiness", value.readiness().name());
            return json;
        }

        private static PopulationAdmissionToken token(JsonObject json) {
            return new PopulationAdmissionToken(
                    UUID.fromString(json.get("operationId").getAsString()),
                    UUID.fromString(json.get("reservationId").getAsString()),
                    json.get("expiresAtMonotonicNanos").getAsLong(),
                    json.get("settingsRevision").getAsLong(),
                    json.get("providerGenerationToken").getAsString(),
                    OwnerPopulationCapDecisionViewV2.Readiness.valueOf(
                            json.get("readiness").getAsString()
                    )
            );
        }
    }
}
