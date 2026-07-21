package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable SHA-256 fingerprint for one bonded-vessel item projection. */
public final class BondedVesselItemFingerprintCodec {
    private static final String HEADER = "TW_BONDED_VESSEL_ITEM_V1";

    /**
     * Canonical UTF-8 payload, with one LF after every line including the final line:
     * <pre>
     * TW_BONDED_VESSEL_ITEM_V1
     * itemId=&lt;base64url-no-padding(UTF-8 item ID)&gt;
     * bindingId=&lt;lowercase UUID&gt;
     * profileId=&lt;base64url-no-padding(UTF-8 profile ID)&gt;
     * generation=&lt;unsigned decimal&gt;
     * configId=&lt;base64url-no-padding(UTF-8 config ID)&gt;
     * state=&lt;BondedVesselState enum name&gt;
     * </pre>
     * The public fingerprint is {@code sha256:} plus 64 lowercase hexadecimal digits.
     */
    @Nonnull
    public String canonicalPayload(@Nonnull VesselItemMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return HEADER + "\n"
                + "itemId=" + encode(metadata.itemId()) + "\n"
                + "bindingId=" + metadata.bindingId().toString().toLowerCase() + "\n"
                + "profileId=" + encode(metadata.profileId()) + "\n"
                + "generation=" + metadata.generation() + "\n"
                + "configId=" + encode(metadata.configId()) + "\n"
                + "state=" + metadata.state().name() + "\n";
    }

    @Nonnull
    public String fingerprint(@Nonnull VesselItemMetadata metadata) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalPayload(metadata).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        return "sha256:" + HexFormat.of().formatHex(digest);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    public record VesselItemMetadata(
            @Nonnull String itemId,
            @Nonnull UUID bindingId,
            @Nonnull String profileId,
            long generation,
            @Nonnull String configId,
            @Nonnull BondedVesselState state
    ) {
        public VesselItemMetadata {
            itemId = requireText(itemId, "itemId");
            bindingId = Objects.requireNonNull(bindingId, "bindingId");
            profileId = requireText(profileId, "profileId");
            configId = requireText(configId, "configId");
            state = Objects.requireNonNull(state, "state");
            if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }
    }
}
