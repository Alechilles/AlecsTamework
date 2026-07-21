package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselItemFingerprintCodec;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselItemFingerprintCodec.VesselItemMetadata;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact bonded-vessel item observation embedded in the sealed population evidence set. */
public final class BondedVesselInventoryEvidence {
    private static final String SUFFIX = "::tamework-bonded-vessel-item-v1:";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern PLAYER_LOCATION = Pattern.compile(
            "^(?:player-save|online-player)/([0-9a-fA-F-]{36})/(.+)$");
    private final BondedVesselItemFingerprintCodec fingerprints =
            new BondedVesselItemFingerprintCodec();

    /** Returns empty only when the stack carries no bonded-vessel marker at all. */
    @Nonnull
    public Optional<CompanionPopulationEvidence> read(
            @Nullable ItemStack stack,
            @Nonnull String evidenceKey,
            @Nonnull String source
    ) {
        if (stack == null || ItemStack.isEmpty(stack)) {
            return Optional.empty();
        }
        String binding = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_BINDING_ID, Codec.STRING);
        String profile = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_PROFILE_ID, Codec.STRING);
        Long generation = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG);
        String config = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_CONFIG_ID, Codec.STRING);
        String state = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_STATE, Codec.STRING);
        if (binding == null && profile == null && generation == null
                && config == null && state == null) {
            return Optional.empty();
        }
        try {
            if (stack.getQuantity() != 1) {
                throw new IllegalArgumentException("bonded vessel item quantity must be one");
            }
            VesselItemMetadata metadata = new VesselItemMetadata(
                    stack.getItemId(), UUID.fromString(binding), profile,
                    Objects.requireNonNull(generation, "generation").longValue(), config,
                    BondedVesselState.valueOf(state));
            String fingerprint = fingerprints.fingerprint(metadata);
            return Optional.of(new CompanionPopulationEvidence(
                    append(evidenceKey, metadata.bindingId(), metadata.generation(), fingerprint),
                    metadata.bindingId(),
                    null,
                    false,
                    CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                    null,
                    null,
                    null,
                    null,
                    source
            ));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Bonded vessel item metadata is incomplete at " + evidenceKey + ".", failure);
        }
    }

    @Nonnull
    public static String append(@Nonnull String baseKey, @Nonnull UUID bindingId,
                                long generation, @Nonnull String fingerprint) {
        String base = requireText(baseKey, "baseKey");
        if (base.contains(SUFFIX) || generation <= 0L
                || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("Bonded vessel item evidence is not canonical.");
        }
        return base + SUFFIX + bindingId.toString().toLowerCase()
                + ":" + generation + ":" + fingerprint;
    }

    @Nullable
    static Observation parse(@Nullable String evidenceKey) {
        if (evidenceKey == null) return null;
        int offset = evidenceKey.indexOf(SUFFIX);
        if (offset <= 0 || offset != evidenceKey.lastIndexOf(SUFFIX)) return null;
        String[] fields = evidenceKey.substring(offset + SUFFIX.length()).split(":", -1);
        if (fields.length != 4) return null;
        try {
            UUID bindingId = UUID.fromString(fields[0]);
            long generation = Long.parseLong(fields[1]);
            String fingerprint = fields[2] + ":" + fields[3];
            if (generation <= 0L || !FINGERPRINT.matcher(fingerprint).matches()
                    || !bindingId.toString().equals(fields[0])) {
                return null;
            }
            return new Observation(
                    bindingId, generation, fingerprint,
                    canonicalLocation(evidenceKey.substring(0, offset)));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    @Nonnull
    private static String canonicalLocation(@Nonnull String baseKey) {
        Matcher player = PLAYER_LOCATION.matcher(baseKey);
        if (!player.matches()) return baseKey;
        UUID playerUuid = UUID.fromString(player.group(1));
        return "player/" + playerUuid.toString().toLowerCase() + "/" + player.group(2);
    }

    @Nonnull
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || !normalized.equals(value)) {
            throw new IllegalArgumentException(field + " must be canonical non-blank text.");
        }
        return normalized;
    }

    /** One unique physical inventory location and the exact generation projected there. */
    public record Observation(@Nonnull UUID bindingId, long generation,
                              @Nonnull String fingerprint,
                              @Nonnull String canonicalLocation) {
        public Observation {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(fingerprint, "fingerprint");
            canonicalLocation = requireText(canonicalLocation, "canonicalLocation");
            if (generation <= 0L || !FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("Bonded vessel item observation is invalid.");
            }
        }
    }
}
