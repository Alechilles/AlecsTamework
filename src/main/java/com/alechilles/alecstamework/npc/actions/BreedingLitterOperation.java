package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.PopulationAdmissionToken;
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
}
