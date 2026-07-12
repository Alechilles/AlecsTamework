package com.alechilles.alecstamework.ownership.reconciliation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Encodes restart-visible projection-marker identity inside a persisted evidence key.
 *
 * <p>The reconciliation schema deliberately remains unchanged. A recognizable suffix carries
 * the marker fingerprint plus both Hytale entity identities, while the fingerprint itself hashes
 * length-prefixed fields so nulls and adjacent values cannot collide through delimiter tricks.</p>
 */
public final class CompanionProjectionEvidence {
    private static final String FINGERPRINT_DOMAIN = "tamework-projection-evidence-v1";
    private static final String SUFFIX_PREFIX_V1 = "::tamework-projection-v1:";
    private static final String SUFFIX_PREFIX_V2 = "::tamework-projection-v2:";
    private static final String NULL_UUID = "~";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private CompanionProjectionEvidence() {
    }

    /** Returns the deterministic identity of all persisted fields in one projection marker. */
    @Nonnull
    public static String fingerprint(
            @Nonnull String profileId,
            @Nonnull String operationId,
            @Nonnull String projectionKind,
            @Nullable String slotKey,
            @Nullable UUID sourceNpcUuid,
            long generation
    ) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream canonical = new DataOutputStream(bytes)) {
                writeRequired(canonical, FINGERPRINT_DOMAIN, "domain");
                writeRequired(canonical, profileId, "profileId");
                writeRequired(canonical, operationId, "operationId");
                writeRequired(canonical, projectionKind, "projectionKind");
                writeNullable(canonical, slotKey);
                writeNullable(canonical, sourceNpcUuid);
                canonical.writeLong(generation);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode projection fingerprint.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /** Appends a strict, schema-neutral projection observation suffix to an evidence key. */
    @Nonnull
    public static String appendToEvidenceKey(
            @Nonnull String baseKey,
            @Nonnull String fingerprint,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid
    ) {
        return appendToEvidenceKey(
                baseKey, fingerprint, componentUuid, legacyNpcUuid, false
        );
    }

    /** Appends projection identity and the saved entity's observed death state. */
    @Nonnull
    public static String appendToEvidenceKey(
            @Nonnull String baseKey,
            @Nonnull String fingerprint,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            boolean deathObserved
    ) {
        String requiredBase = requireText(baseKey, "baseKey");
        String requiredFingerprint = requireFingerprint(fingerprint);
        if (containsReservedSuffix(requiredBase)) {
            throw new IllegalArgumentException("baseKey contains the reserved projection suffix.");
        }
        return requiredBase + SUFFIX_PREFIX_V2 + requiredFingerprint
                + ":" + encodeUuid(componentUuid)
                + ":" + encodeUuid(legacyNpcUuid)
                + ":" + (deathObserved ? "1" : "0");
    }

    /** Parses only a complete suffix produced by {@link #appendToEvidenceKey}. */
    @Nullable
    public static ProjectionObservation parseEvidenceKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        Suffix suffix = suffix(key);
        if (suffix == null) {
            return null;
        }
        String[] fields = key.substring(suffix.offset() + suffix.prefix().length()).split(":", -1);
        int expectedFields = suffix.version() == 1 ? 3 : 4;
        if (fields.length != expectedFields || !FINGERPRINT.matcher(fields[0]).matches()) {
            return null;
        }
        UUID componentUuid = decodeUuid(fields[1]);
        UUID legacyNpcUuid = decodeUuid(fields[2]);
        if (!validUuidToken(fields[1], componentUuid) || !validUuidToken(fields[2], legacyNpcUuid)) {
            return null;
        }
        Boolean deathObserved = suffix.version() == 1 ? Boolean.FALSE : decodeBoolean(fields[3]);
        if (deathObserved == null) {
            return null;
        }
        return new ProjectionObservation(
                fields[0], componentUuid, legacyNpcUuid, deathObserved.booleanValue()
        );
    }

    static boolean containsReservedSuffix(@Nullable String key) {
        return key != null
                && (key.contains(SUFFIX_PREFIX_V1) || key.contains(SUFFIX_PREFIX_V2));
    }

    @Nullable
    private static Suffix suffix(String key) {
        int v1 = key.indexOf(SUFFIX_PREFIX_V1);
        int v2 = key.indexOf(SUFFIX_PREFIX_V2);
        if ((v1 >= 0) == (v2 >= 0)) {
            return null;
        }
        String prefix = v1 >= 0 ? SUFFIX_PREFIX_V1 : SUFFIX_PREFIX_V2;
        int offset = Math.max(v1, v2);
        if (offset <= 0 || offset != key.lastIndexOf(prefix)) {
            return null;
        }
        return new Suffix(v1 >= 0 ? 1 : 2, offset, prefix);
    }

    private static void writeRequired(DataOutputStream output, String value, String field)
            throws IOException {
        writeBytes(output, requireText(value, field).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeNullable(DataOutputStream output, @Nullable String value)
            throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        writeBytes(output, requireText(value, "slotKey").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeNullable(DataOutputStream output, @Nullable UUID value)
            throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeLong(value.getMostSignificantBits());
            output.writeLong(value.getLeastSignificantBits());
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    @Nonnull
    private static String encodeUuid(@Nullable UUID uuid) {
        return uuid == null ? NULL_UUID : uuid.toString();
    }

    @Nullable
    private static UUID decodeUuid(String token) {
        if (NULL_UUID.equals(token)) {
            return null;
        }
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean validUuidToken(String token, @Nullable UUID decoded) {
        return decoded == null ? NULL_UUID.equals(token) : decoded.toString().equals(token);
    }

    @Nullable
    private static Boolean decodeBoolean(String token) {
        return switch (token) {
            case "0" -> Boolean.FALSE;
            case "1" -> Boolean.TRUE;
            default -> null;
        };
    }

    @Nonnull
    private static String requireFingerprint(String value) {
        String required = Objects.requireNonNull(value, "fingerprint");
        if (!FINGERPRINT.matcher(required).matches()) {
            throw new IllegalArgumentException("fingerprint must be 64 lowercase hexadecimal characters.");
        }
        return required;
    }

    @Nonnull
    private static String requireText(String value, String field) {
        String required = Objects.requireNonNull(value, field);
        if (required.isBlank() || !required.equals(required.trim())) {
            throw new IllegalArgumentException(field + " must be canonical non-blank text.");
        }
        return required;
    }

    /** Parsed persisted marker identity, independent of the ordinary NPC evidence identity. */
    public record ProjectionObservation(
            @Nonnull String fingerprint,
            @Nullable UUID componentUuid,
            @Nullable UUID legacyNpcUuid,
            boolean deathObserved
    ) {
        public ProjectionObservation {
            fingerprint = requireFingerprint(fingerprint);
        }

        /** Compatibility constructor for version-one evidence, which implied a live entity. */
        public ProjectionObservation(
                @Nonnull String fingerprint,
                @Nullable UUID componentUuid,
                @Nullable UUID legacyNpcUuid
        ) {
            this(fingerprint, componentUuid, legacyNpcUuid, false);
        }
    }

    private record Suffix(int version, int offset, String prefix) {
    }
}
