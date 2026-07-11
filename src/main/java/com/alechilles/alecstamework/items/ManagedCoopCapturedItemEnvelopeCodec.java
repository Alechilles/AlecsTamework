package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec.DecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec.Status;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Strict versioned metadata envelope for moving a complete captured-NPC snapshot into a managed coop.
 *
 * <p>The existing loose spawner keys cannot distinguish an intentionally absent component from a
 * partially written item. This envelope therefore carries one complete portable snapshot plus its
 * canonical profile and hash. Intake rejects legacy/partial items instead of synthesizing state.</p>
 */
public final class ManagedCoopCapturedItemEnvelopeCodec {
    public static final String METADATA_KEY = "Tamework.ManagedCoop.CapturedItemV1";
    public static final String CURRENT_VERSION = "1";

    public enum DecodeStatus {
        FOUND,
        NOT_FOUND,
        FAILED
    }

    /** Immutable, verified item evidence used by the intake transaction. */
    public record Envelope(@Nonnull String profileId,
                           @Nonnull UUID sourceNpcUuid,
                           @Nonnull String roleId,
                           @Nullable UUID ownerUuid,
                           @Nullable String displayName,
                           @Nonnull String[] toolIds,
                           @Nonnull CoopResidentStateSnapshot portableSnapshot,
                           @Nonnull String portableSnapshotJson,
                           @Nonnull String portableSnapshotHash,
                           int snapshotVersion,
                           @Nonnull String fingerprint) {
        public Envelope {
            profileId = requireText(profileId, "profileId");
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            roleId = normalizeRequired(roleId, "roleId");
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
            Objects.requireNonNull(portableSnapshot, "portableSnapshot");
            portableSnapshotJson = requireTextPreserving(
                    portableSnapshotJson, "portableSnapshotJson");
            portableSnapshotHash = requireSha256(portableSnapshotHash, "portableSnapshotHash");
            fingerprint = requireSha256(fingerprint, "fingerprint");
            if (snapshotVersion < 1) {
                throw new IllegalArgumentException("snapshotVersion must be positive");
            }
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }
    }

    public record DecodeOutcome(@Nonnull DecodeStatus status,
                                @Nullable Envelope envelope,
                                @Nullable String detail) {
        public DecodeOutcome {
            Objects.requireNonNull(status, "status");
        }

        public boolean found() {
            return status == DecodeStatus.FOUND && envelope != null;
        }
    }

    private final CoopResidentStateSnapshotCodec snapshots;

    public ManagedCoopCapturedItemEnvelopeCodec() {
        this(new CoopResidentStateSnapshotCodec());
    }

    ManagedCoopCapturedItemEnvelopeCodec(@Nonnull CoopResidentStateSnapshotCodec snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /** Creates metadata only from an already complete, portable snapshot and stable profile. */
    @Nonnull
    public String encode(@Nonnull String profileId,
                         @Nonnull CoopResidentStateSnapshot portableSnapshot) {
        validatePortable(portableSnapshot);
        String normalizedProfileId = requireText(profileId, "profileId");
        String snapshotJson = snapshots.encode(portableSnapshot);
        String snapshotHash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("profileId", normalizedProfileId);
        root.addProperty("snapshotVersion", Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION));
        root.addProperty("snapshotJson", snapshotJson);
        root.addProperty("snapshotHash", snapshotHash);
        return root.toString();
    }

    /** Decodes and cross-checks the envelope, nested snapshot, identity, and item fingerprint. */
    @Nonnull
    public DecodeOutcome decode(@Nullable String itemId, @Nullable String rawEnvelope) {
        if (rawEnvelope == null || rawEnvelope.isBlank()) {
            return new DecodeOutcome(DecodeStatus.NOT_FOUND, null, "managed_coop_item_envelope_missing");
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawEnvelope);
            if (!parsed.isJsonObject()) {
                return failed("managed_coop_item_envelope_root_invalid");
            }
            return decodeObject(requireText(itemId, "itemId"), parsed.getAsJsonObject());
        } catch (RuntimeException exception) {
            return failed("managed_coop_item_envelope_invalid:" + exceptionName(exception));
        }
    }

    private DecodeOutcome decodeObject(String itemId, JsonObject root) {
        String version = requiredString(root, "version");
        if (!CURRENT_VERSION.equals(version)) {
            return failed("managed_coop_item_envelope_version_unsupported");
        }
        String profileId = requiredString(root, "profileId");
        int snapshotVersion = requiredInt(root, "snapshotVersion");
        if (snapshotVersion != Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION)) {
            return failed("managed_coop_item_snapshot_version_unsupported");
        }
        String snapshotJson = requiredStringPreserving(root, "snapshotJson");
        String snapshotHash = requireSha256(requiredString(root, "snapshotHash"), "snapshotHash");
        if (!snapshotHash.equals(ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson))) {
            return failed("managed_coop_item_snapshot_hash_mismatch");
        }
        DecodeResult nested = snapshots.decode(snapshotJson);
        if (nested.status() != Status.FOUND || nested.snapshot() == null) {
            return failed("managed_coop_item_snapshot_invalid:" + nested.failure());
        }
        CoopResidentStateSnapshot snapshot = nested.snapshot();
        validatePortable(snapshot);
        UUID ownerUuid = resolveOwner(snapshot);
        String[] toolIds = resolveTools(snapshot);
        String displayName = snapshot.npcName() != null
                ? normalizeOptional(snapshot.npcName().getName()) : null;
        String fingerprint = fingerprint(
                itemId, profileId, snapshot.npcUuid(), snapshotHash, snapshot.capturedAtMs());
        return new DecodeOutcome(
                DecodeStatus.FOUND,
                new Envelope(
                        profileId,
                        snapshot.npcUuid(),
                        snapshot.roleId(),
                        ownerUuid,
                        displayName,
                        toolIds,
                        snapshot,
                        snapshotJson,
                        snapshotHash,
                        snapshotVersion,
                        fingerprint
                ),
                null
        );
    }

    private void validatePortable(@Nullable CoopResidentStateSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            throw new IllegalArgumentException("portable snapshot source UUID is required");
        }
        if (snapshot.coopId() != null || snapshot.residentSlot() != -1) {
            throw new IllegalArgumentException("portable snapshot must not already name a coop slot");
        }
        normalizeRequired(snapshot.roleId(), "portableSnapshot.roleId");
        if (snapshot.capturedAtMs() == 0L) {
            throw new IllegalArgumentException("portable snapshot capture timestamp is required");
        }
        UUID owner = snapshot.owner() != null ? snapshot.owner().getOwnerId() : null;
        UUID linkOwner = snapshot.commandLinks() != null ? snapshot.commandLinks().getOwnerId() : null;
        if (owner != null && linkOwner != null && !owner.equals(linkOwner)) {
            throw new IllegalArgumentException("portable snapshot owner identity is inconsistent");
        }
    }

    @Nullable
    private UUID resolveOwner(CoopResidentStateSnapshot snapshot) {
        TameworkOwnerComponent owner = snapshot.owner();
        if (owner != null && owner.getOwnerId() != null) {
            return owner.getOwnerId();
        }
        TameworkCommandLinksComponent links = snapshot.commandLinks();
        return links != null ? links.getOwnerId() : null;
    }

    @Nonnull
    private String[] resolveTools(CoopResidentStateSnapshot snapshot) {
        TameworkCommandLinksComponent links = snapshot.commandLinks();
        if (links == null || links.getToolIds() == null) {
            return new String[0];
        }
        return Arrays.stream(links.getToolIds())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }

    @Nonnull
    private String fingerprint(String itemId,
                               String profileId,
                               UUID sourceNpcUuid,
                               String snapshotHash,
                               long capturedAtMs) {
        String identity = token(itemId)
                + token(profileId)
                + token(sourceNpcUuid.toString())
                + token(snapshotHash)
                + token(Long.toString(capturedAtMs));
        return sha256(identity);
    }

    @Nonnull
    private DecodeOutcome failed(String detail) {
        return new DecodeOutcome(DecodeStatus.FAILED, null, detail);
    }

    @Nonnull
    private String requiredString(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("envelope field must be a string: " + field);
        }
        return requireText(value.getAsString(), field);
    }

    @Nonnull
    private String requiredStringPreserving(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("envelope field must be a string: " + field);
        }
        return requireTextPreserving(value.getAsString(), field);
    }

    private int requiredInt(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("envelope field must be an integer: " + field);
        }
        return value.getAsBigDecimal().intValueExact();
    }

    @Nonnull
    private static String normalizeRequired(@Nullable String value, String field) {
        return requireText(value, field).toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    private static String requireTextPreserving(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Nonnull
    private static String requireSha256(@Nullable String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical lowercase SHA-256");
        }
        return value;
    }

    @Nonnull
    private static String token(String value) {
        String required = requireText(value, "fingerprint token");
        return required.length() + ":" + required;
    }

    @Nonnull
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Nonnull
    private static String exceptionName(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
