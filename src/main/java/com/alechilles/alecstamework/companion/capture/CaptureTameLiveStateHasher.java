package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Canonical fingerprint of the live NPC authorities changed by tame-and-link capture.
 *
 * <p>The operation receipt is deliberately excluded. It is matched field-for-field as separate
 * positive evidence, while this digest proves the exact gameplay state before or after the
 * mutation.</p>
 */
public final class CaptureTameLiveStateHasher {
    private static final String FORMAT = "tamework:capture-tame-live-state:v1";

    private CaptureTameLiveStateHasher() {
    }

    /** Hashes one immutable, engine-neutral live-state observation. */
    @Nonnull
    public static Sha256Hash hash(@Nonnull State state) {
        if (state == null) {
            throw new IllegalArgumentException("Tame live state is required");
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, FORMAT);
        append(canonical, state.roleId());
        append(canonical, Boolean.toString(state.ownerPresent()));
        append(canonical, text(state.ownerId()));
        append(canonical, nullable(state.ownerName()));
        append(canonical, Boolean.toString(state.tamedPresent()));
        append(canonical, Boolean.toString(state.tamed()));
        append(canonical, Boolean.toString(state.commandLinksPresent()));
        append(canonical, text(state.commandOwnerId()));
        append(canonical, Integer.toString(state.commandLinkIds().size()));
        state.commandLinkIds().forEach(value -> append(canonical, value));
        append(canonical, Boolean.toString(state.commandHomePresent()));
        append(canonical, Double.toString(state.homeX()));
        append(canonical, Double.toString(state.homeY()));
        append(canonical, Double.toString(state.homeZ()));
        append(canonical, Integer.toString(state.spawnConfigurationIndex()));
        append(canonical, Integer.toString(state.environmentIndex()));
        append(canonical, Boolean.toString(state.spawnMarkerPresent()));
        append(canonical, Boolean.toString(state.spawnBeaconPresent()));
        return Sha256Hash.ofUtf8(canonical.toString());
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullable(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static void append(StringBuilder target, String value) {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        target.append(byteLength).append(':').append(value);
    }

    /**
     * Immutable state observed on the world thread or constructed as a frozen target.
     */
    public record State(
            @Nonnull String roleId,
            boolean ownerPresent,
            @Nullable OwnerId ownerId,
            @Nullable String ownerName,
            boolean tamedPresent,
            boolean tamed,
            boolean commandLinksPresent,
            @Nullable OwnerId commandOwnerId,
            @Nonnull List<String> commandLinkIds,
            boolean commandHomePresent,
            double homeX,
            double homeY,
            double homeZ,
            int spawnConfigurationIndex,
            int environmentIndex,
            boolean spawnMarkerPresent,
            boolean spawnBeaconPresent
    ) {
        public State {
            if (roleId == null || roleId.isBlank()
                    || commandLinkIds == null
                    || !Double.isFinite(homeX)
                    || !Double.isFinite(homeY)
                    || !Double.isFinite(homeZ)) {
                throw new IllegalArgumentException(
                        "Complete finite tame live state is required"
                );
            }
            roleId = roleId.trim();
            TreeSet<String> links = new TreeSet<>();
            for (String link : commandLinkIds) {
                if (link == null || link.isBlank()) {
                    throw new IllegalArgumentException(
                            "Command link IDs must be nonblank"
                    );
                }
                links.add(link.trim());
            }
            commandLinkIds = List.copyOf(links);
            if (!ownerPresent && (ownerId != null || ownerName != null)) {
                throw new IllegalArgumentException(
                        "Absent owner component cannot contain owner state"
                );
            }
            if (!tamedPresent && tamed) {
                throw new IllegalArgumentException(
                        "Absent tamed component cannot be true"
                );
            }
            if (!commandLinksPresent
                    && (commandOwnerId != null
                    || !commandLinkIds.isEmpty()
                    || commandHomePresent)) {
                throw new IllegalArgumentException(
                        "Absent command links cannot contain link state"
                );
            }
            if (!commandHomePresent
                    && (homeX != 0.0D || homeY != 0.0D || homeZ != 0.0D)) {
                throw new IllegalArgumentException(
                        "Absent command home must use zero coordinates"
                );
            }
        }
    }
}
