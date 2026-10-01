package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The identity a capture item carries (spec 12.4): the profile id and the generation the record
 * had when the item was made. The companion's state lives in the snapshot store, not on the item.
 * An item without a generation key was written before generations existed and counts as 0.
 */
public final class CaptureItemKeys {
    /** The profile an item refers to and the generation it was made at. */
    public record Ref(@Nonnull UUID profileId, long generation) {
        public Ref {
            Objects.requireNonNull(profileId, "profileId");
        }
    }

    private CaptureItemKeys() {
    }

    /** Null when the stack has no profile id or the id or generation cannot be read. */
    @Nullable
    public static Ref read(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            String id = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING);
            if (id == null) {
                return null;
            }
            Long generation = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMPANION_GENERATION, Codec.LONG);
            if (generation != null && generation < 0) {
                return null;
            }
            return new Ref(UUID.fromString(id), generation == null ? 0L : generation);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Like {@link #read}, but null for an older item that also carries a capture snapshot id
     * (4.x shape, or a damaged one): only a profile id without a snapshot id is a 5.0 index item.
     */
    @Nullable
    public static Ref readIndexItem(@Nullable ItemStack stack) {
        Ref ref = read(stack);
        if (ref == null) {
            return null;
        }
        try {
            return stack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID, Codec.STRING) == null ? ref : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A copy of {@code stack} carrying {@code ref}. */
    @Nonnull
    public static ItemStack write(@Nonnull ItemStack stack, @Nonnull Ref ref) {
        return stack.withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, ref.profileId().toString())
                .withMetadata(TameworkMetadataKeys.COMPANION_GENERATION, Codec.LONG, ref.generation());
    }
}
