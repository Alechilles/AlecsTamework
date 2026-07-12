package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Canonical immutable evidence boundary for one Hytale 0.5.6 vanilla-coop import.
 *
 * <p>Stable source identity deliberately excludes list slot and iteration order. Those values are
 * retained only as locator hints, so removing an earlier resident cannot change the identity of a
 * later resident during replay.</p>
 */
public final class VanillaCoopImportEvidenceCodec {
    /** Version two binds operator approval to the exact durable source plans. */
    public static final int AUDIT_VERSION = 2;
    public static final int SOURCE_ENVELOPE_VERSION = 1;

    public enum PlannedDisposition {
        MATCHED,
        IMPORTED,
        QUARANTINED
    }

    /** Stable values copied synchronously from a vanilla resident. */
    public record StableSource(@Nonnull String payload,
                               @Nonnull String fingerprint,
                               int sourceSlot,
                               int sourceOrder,
                               boolean metadataPresent,
                               @Nullable UUID persistentUuid,
                               boolean deployedToWorld,
                               @Nullable String lastProduced,
                               @Nullable String roleId,
                               @Nullable String displayName,
                               @Nonnull List<String> unavailableFields) {
        public StableSource {
            payload = requirePayload(payload, "payload");
            fingerprint = requireHash(fingerprint, "fingerprint");
            if (sourceSlot < 0 || sourceOrder < 0) {
                throw new IllegalArgumentException("source slot and order must not be negative");
            }
            roleId = optionalLower(roleId);
            displayName = optionalText(displayName);
            unavailableFields = unavailableFields == null ? List.of() : List.copyOf(unavailableFields);
        }

        public boolean importSupported() {
            return unavailableFields.isEmpty();
        }
    }

    /** Durable decision decoded from a source envelope after restart. */
    public record SourcePlan(@Nonnull PlannedDisposition disposition,
                             @Nonnull String stablePayload,
                             int multiplicity,
                             @Nullable String residentId,
                             @Nullable String profileId,
                             @Nullable UUID residentUuid,
                             @Nullable Integer targetSlot,
                             @Nullable String roleId,
                             @Nullable String conflictKind,
                             boolean overflow) {
        /** Preserves source compatibility for plans written before overflow was diagnostic data. */
        public SourcePlan(PlannedDisposition disposition,
                          String stablePayload,
                          int multiplicity,
                          String residentId,
                          String profileId,
                          UUID residentUuid,
                          Integer targetSlot,
                          String roleId,
                          String conflictKind) {
            this(disposition, stablePayload, multiplicity, residentId, profileId, residentUuid,
                    targetSlot, roleId, conflictKind, false);
        }

        public SourcePlan {
            Objects.requireNonNull(disposition, "disposition");
            stablePayload = requirePayload(stablePayload, "stablePayload");
            if (multiplicity < 1) {
                throw new IllegalArgumentException("multiplicity must be positive");
            }
            residentId = optionalText(residentId);
            profileId = optionalText(profileId);
            roleId = optionalLower(roleId);
            conflictKind = optionalText(conflictKind);
            if (targetSlot != null && targetSlot < 0) {
                throw new IllegalArgumentException("targetSlot must not be negative");
            }
            validatePlanShape(
                    disposition,
                    multiplicity,
                    residentId,
                    profileId,
                    residentUuid,
                    targetSlot,
                    roleId,
                    conflictKind
            );
            if (overflow && disposition != PlannedDisposition.IMPORTED) {
                throw new IllegalArgumentException("only an imported source may be overflow");
            }
        }
    }

    /** Copies all supported source values before any asynchronous persistence begins. */
    @Nonnull
    public StableSource copyStableSource(
            @Nonnull VanillaCoopImportAdapter.ResidentEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        CapturedNPCMetadata metadata = evidence.rawMetadata();
        ArrayList<String> unavailable = new ArrayList<>();
        String roleId = null;
        String iconPath = null;
        String fullItemIcon = null;
        boolean alarmStorePresent = false;
        if (metadata == null) {
            unavailable.add("captured_metadata_missing");
        } else {
            roleId = optionalLower(metadata.getNpcNameKey());
            iconPath = optionalText(metadata.getIconPath());
            fullItemIcon = optionalText(metadata.getFullItemIcon());
            alarmStorePresent = metadata.getAlarmStore() != null;
            if (roleId == null) {
                unavailable.add("role_id_missing");
            }
            if (alarmStorePresent) {
                unavailable.add("alarm_store_not_portable");
            }
        }
        if (evidence.deployedToWorld() && evidence.persistentUuid() == null) {
            unavailable.add("deployed_source_uuid_missing");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("version", SOURCE_ENVELOPE_VERSION);
        payload.addProperty("metadataPresent", metadata != null);
        putOptional(payload, "roleId", roleId);
        putOptional(payload, "iconPath", iconPath);
        putOptional(payload, "fullItemIcon", fullItemIcon);
        payload.addProperty("alarmStorePresent", alarmStorePresent);
        payload.addProperty("persistentRefPresent", evidence.rawPersistentRef() != null);
        putOptional(payload, "persistentUuid", evidence.persistentUuid());
        payload.addProperty("deployedToWorld", evidence.deployedToWorld());
        String lastProduced = evidence.lastProduced() == null
                ? null : evidence.lastProduced().toString();
        putOptional(payload, "lastProduced", lastProduced);
        String encoded = payload.toString();
        return new StableSource(
                encoded,
                sha256(encoded),
                evidence.residentSlot(),
                evidence.sourceOrder(),
                metadata != null,
                evidence.persistentUuid(),
                evidence.deployedToWorld(),
                lastProduced,
                roleId,
                null,
                unavailable
        );
    }

    /** Encodes produce evidence without removing or replacing any vanilla inventory stack. */
    @Nonnull
    public String copyProducePayload(@Nullable ItemContainer container) {
        JsonObject root = new JsonObject();
        root.addProperty("retainedInVanillaStorage", true);
        if (container == null) {
            root.addProperty("containerPresent", false);
            root.add("slots", new JsonArray());
            return root.toString();
        }
        root.addProperty("containerPresent", true);
        root.addProperty("containerClass", container.getClass().getName());
        root.addProperty("capacity", container.getCapacity());
        JsonArray slots = new JsonArray();
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            if (!ItemStack.isEmpty(stack)) {
                item.addProperty("itemId", stack.getItemId());
                item.addProperty("quantity", stack.getQuantity());
                item.addProperty("metadataPresent", stack.getMetadata() != null
                        && !stack.getMetadata().isEmpty());
            }
            slots.add(item);
        }
        root.add("slots", slots);
        return root.toString();
    }

    /** Builds the immutable source envelope persisted beside the exact source payload. */
    @Nonnull
    public String encodeSourceEnvelope(@Nonnull String sourceFingerprint,
                                       @Nonnull SourcePlan plan,
                                       int sourceSlot,
                                       int sourceOrder) {
        JsonObject root = new JsonObject();
        root.addProperty("version", SOURCE_ENVELOPE_VERSION);
        root.addProperty("sourceFingerprint", requireHash(sourceFingerprint, "sourceFingerprint"));
        root.addProperty("stablePayload", plan.stablePayload());
        JsonObject locator = new JsonObject();
        locator.addProperty("originalSlot", sourceSlot);
        locator.addProperty("originalOrder", sourceOrder);
        root.add("locator", locator);
        JsonObject decision = new JsonObject();
        decision.addProperty("disposition", plan.disposition().name());
        decision.addProperty("multiplicity", plan.multiplicity());
        putOptional(decision, "residentId", plan.residentId());
        putOptional(decision, "profileId", plan.profileId());
        putOptional(decision, "residentUuid", plan.residentUuid());
        if (plan.targetSlot() != null) {
            decision.addProperty("targetSlot", plan.targetSlot());
        }
        putOptional(decision, "roleId", plan.roleId());
        putOptional(decision, "conflictKind", plan.conflictKind());
        if (plan.overflow()) {
            decision.addProperty("overflow", true);
        }
        root.add("plan", decision);
        return root.toString();
    }

    /** Strictly reconstructs a durable plan and cross-checks its immutable source evidence. */
    @Nonnull
    public SourcePlan decodeSourcePlan(@Nonnull SourceEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        JsonElement parsed = JsonParser.parseString(evidence.sourceEnvelopeJson());
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("source envelope root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (requiredInt(root, "version") != SOURCE_ENVELOPE_VERSION
                || !evidence.sourceFingerprint().equals(requiredString(root, "sourceFingerprint"))) {
            throw new IllegalArgumentException("source envelope version or fingerprint mismatch");
        }
        String stablePayload = requiredStringPreserving(root, "stablePayload");
        JsonObject plan = requiredObject(root, "plan");
        SourcePlan decoded = new SourcePlan(
                PlannedDisposition.valueOf(requiredString(plan, "disposition")),
                stablePayload,
                requiredInt(plan, "multiplicity"),
                optionalString(plan, "residentId"),
                optionalString(plan, "profileId"),
                optionalUuid(plan, "residentUuid"),
                optionalInt(plan, "targetSlot"),
                optionalString(plan, "roleId"),
                optionalString(plan, "conflictKind"),
                optionalBoolean(plan, "overflow", false)
        );
        String expectedPayload = decoded.multiplicity() == 1
                ? decoded.stablePayload()
                : groupedPayload(decoded.stablePayload(), decoded.multiplicity());
        if (!expectedPayload.equals(evidence.sourcePayload())
                || !sha256(expectedPayload).equals(evidence.sourceFingerprint())) {
            throw new IllegalArgumentException("source envelope payload does not verify");
        }
        return decoded;
    }

    /** Canonical payload used to journal an indistinguishable duplicate group without deleting it. */
    @Nonnull
    public String groupedPayload(@Nonnull String stablePayload, int multiplicity) {
        if (multiplicity < 2) {
            throw new IllegalArgumentException("duplicate group multiplicity must be at least two");
        }
        JsonObject root = new JsonObject();
        root.addProperty("kind", "indistinguishable_duplicate_group");
        root.addProperty("multiplicity", multiplicity);
        root.addProperty("stablePayload", requirePayload(stablePayload, "stablePayload"));
        return root.toString();
    }

    /** Creates a portable managed snapshot using only copied vanilla evidence. */
    @Nonnull
    public String managedSnapshot(@Nonnull UUID residentUuid,
                                  @Nonnull String coopId,
                                  int residentSlot,
                                  @Nonnull String roleId,
                                  long capturedAtMs) {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        residentUuid,
                        requireText(coopId, "coopId"),
                        residentSlot,
                        requireText(roleId, "roleId"),
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null,
                        requireTimestamp(capturedAtMs)
                );
        return new CoopResidentStateSnapshotCodec().encode(snapshot);
    }

    /** Deterministic lowercase SHA-256 used by every import evidence layer. */
    @Nonnull
    public static String sha256(@Nonnull String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(Objects.requireNonNull(value, "value")
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Canonical current-source counts used by exact restart absence verification. */
    @Nonnull
    public List<String> sortedStablePayloads(
            @Nonnull VanillaCoopImportAdapter.AuditResult audit) {
        ArrayList<String> payloads = new ArrayList<>();
        for (VanillaCoopImportAdapter.ResidentEvidence resident : audit.residents()) {
            payloads.add(copyStableSource(resident).payload());
        }
        payloads.sort(Comparator.naturalOrder());
        return List.copyOf(payloads);
    }

    @Nonnull
    public String unavailableFieldsJson(@Nonnull List<String> fields) {
        JsonArray array = new JsonArray();
        fields.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
                .distinct().sorted().forEach(array::add);
        JsonObject root = new JsonObject();
        root.add("fields", array);
        return root.toString();
    }

    private static void validatePlanShape(PlannedDisposition disposition,
                                          int multiplicity,
                                          String residentId,
                                          String profileId,
                                          UUID residentUuid,
                                          Integer targetSlot,
                                          String roleId,
                                          String conflictKind) {
        boolean managed = disposition != PlannedDisposition.QUARANTINED;
        if (managed != (residentId != null && profileId != null
                && residentUuid != null && targetSlot != null && roleId != null)) {
            throw new IllegalArgumentException("managed import plan identity is incomplete");
        }
        if ((disposition == PlannedDisposition.QUARANTINED) != (conflictKind != null)) {
            throw new IllegalArgumentException("quarantine plan conflict kind is inconsistent");
        }
        if (managed && multiplicity != 1) {
            throw new IllegalArgumentException("only a unique source may be neutralized");
        }
    }

    private static long requireTimestamp(long value) {
        if (value == 0L) {
            throw new IllegalArgumentException("timestamp must use a non-zero signed value");
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("source envelope object missing: " + field);
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject root, String field) {
        String value = optionalString(root, field);
        if (value == null) {
            throw new IllegalArgumentException("source envelope string missing: " + field);
        }
        return value;
    }

    private static String requiredStringPreserving(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("source envelope string missing: " + field);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("source envelope integer missing: " + field);
        }
        return value.getAsBigDecimal().intValueExact();
    }

    @Nullable
    private static Integer optionalInt(JsonObject root, String field) {
        return root.has(field) ? requiredInt(root, field) : null;
    }

    private static boolean optionalBoolean(JsonObject root, String field, boolean defaultValue) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("source envelope field is not a boolean: " + field);
        }
        return value.getAsBoolean();
    }

    @Nullable
    private static String optionalString(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("source envelope field is not a string: " + field);
        }
        return optionalText(value.getAsString());
    }

    @Nullable
    private static UUID optionalUuid(JsonObject root, String field) {
        String value = optionalString(root, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static void putOptional(JsonObject target, String field, @Nullable Object value) {
        if (value != null) {
            target.addProperty(field, value.toString());
        }
    }

    private static String requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical lowercase SHA-256");
        }
        return value;
    }

    private static String requirePayload(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nullable
    private static String optionalText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nullable
    private static String optionalLower(@Nullable String value) {
        String normalized = optionalText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
