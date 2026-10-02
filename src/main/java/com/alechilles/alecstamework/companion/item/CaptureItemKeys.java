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
 * An item without a generation key was written by 4.x, before generations existed, and counts as 0.
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
     * The index item a stack is: a 5.0 item (profile id and generation), or a 4.x item (profile
     * id, the old capture snapshot id and no generation), which counts as generation 0 (plan 7
     * R17). Records imported from 4.x start at generation 0, so the first release of a 4.x item
     * matches and a copy of it is stale afterwards. The index is not read here: every caller
     * compares the result with the record, and an item whose record is missing, is not in an item
     * or has moved on is stale to all of them. The old keys stay on the item until it is rewritten.
     *
     * <p>Null for anything else, including a 2.x item, which carries no profile id; a release
     * adopts that one through {@code LegacyItemAdoption}.
     */
    @Nullable
    public static Ref readIndexItem(@Nullable ItemStack stack) {
        return read(stack);
    }

    /** A copy of {@code stack} carrying {@code ref}. */
    @Nonnull
    public static ItemStack write(@Nonnull ItemStack stack, @Nonnull Ref ref) {
        return stack.withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, ref.profileId().toString())
                .withMetadata(TameworkMetadataKeys.COMPANION_GENERATION, Codec.LONG, ref.generation());
    }
}
