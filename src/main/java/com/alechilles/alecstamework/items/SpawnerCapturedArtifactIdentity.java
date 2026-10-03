package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Classifies the supported captured-spawner identity shapes.
 *
 * <p>Current artifacts carry the exact profile, source alias, and capture snapshot. Public
 * releases through 2.16.1 carry only the source alias. A snapshot id without a profile id is
 * rejected so damaged current artifacts cannot silently enter the legacy compatibility path.</p>
 *
 * <p>A profile id without a snapshot id is read as a companion-index (5.0) item: a profile id and
 * a generation, no snapshot id. {@link #isSupported} accepts it, but {@link #parse} and its
 * {@link Claim} describe only the two shapes above: the old release and recovery flows need a
 * source alias and snapshot id that a 5.0 item does not have. Read a 5.0 item with
 * {@link CaptureItemKeys#readIndexItem}.</p>
 */
public final class SpawnerCapturedArtifactIdentity {
    private SpawnerCapturedArtifactIdentity() {
    }

    /** Returns whether a stack has one complete supported identity shape. */
    public static boolean isSupported(@Nullable ItemStack stack) {
        return parse(stack) != null || CaptureItemKeys.readIndexItem(stack) != null;
    }

    /** Parses a complete current or released-public identity claim. */
    @Nullable
    static Claim parse(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) {
            return null;
        }
        boolean hasProfileKey = metadata.containsKey(
                TameworkMetadataKeys.COMPANION_PROFILE_ID
        );
        boolean hasSnapshotKey = metadata.containsKey(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
        );
        if (hasProfileKey != hasSnapshotKey) {
            return null;
        }
        try {
            NpcAlias sourceAlias = new NpcAlias(uuid(
                    metadata,
                    TameworkMetadataKeys.TARGET_UUID
            ));
            if (!hasProfileKey) {
                return new Claim(
                        Mode.RELEASED_PUBLIC,
                        sourceAlias,
                        null,
                        null
                );
            }
            return new Claim(
                    Mode.CURRENT,
                    sourceAlias,
                    new ProfileId(uuid(
                            metadata,
                            TameworkMetadataKeys.COMPANION_PROFILE_ID
                    )),
                    SnapshotId.parse(text(
                            metadata,
                            TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                    ))
            );
        } catch (IllegalArgumentException | NullPointerException invalidIdentity) {
            return null;
        }
    }

    private static UUID uuid(BsonDocument metadata, String key) {
        return UUID.fromString(text(metadata, key));
    }

    private static String text(BsonDocument metadata, String key) {
        BsonValue value = metadata.get(key);
        if (value == null || !value.isString()
                || value.asString().getValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Captured-artifact identity metadata is invalid: " + key
            );
        }
        return value.asString().getValue();
    }

    enum Mode {
        CURRENT,
        RELEASED_PUBLIC
    }

    record Claim(
            @Nonnull Mode mode,
            @Nonnull NpcAlias sourceAlias,
            @Nullable ProfileId profileId,
            @Nullable SnapshotId snapshotId
    ) {
        Claim {
            if (mode == null || sourceAlias == null
                    || (mode == Mode.CURRENT)
                    != (profileId != null && snapshotId != null)) {
                throw new IllegalArgumentException(
                        "Complete captured-artifact identity claim is required"
                );
            }
        }

        boolean releasedPublic() {
            return mode == Mode.RELEASED_PUBLIC;
        }
    }
}
