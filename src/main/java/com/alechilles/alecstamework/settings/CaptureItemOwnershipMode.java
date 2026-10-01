package com.alechilles.alecstamework.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Server-wide rule for who owns a companion held by a capture item ({@code captureItemOwnership}
 * in `/tw settings`). A captured companion keeps an owner in every mode; the mode decides how
 * that owner changes.
 */
public enum CaptureItemOwnershipMode {
    /** The owner is whoever gets the filled item into their inventory, if their limits allow. */
    FOLLOWS_ITEM("FOLLOWS_ITEM"),
    /** Only the owner can pick up or release the filled item. */
    OWNER_ONLY("OWNER_ONLY"),
    /** The owner stays while the companion is in the item; whoever releases it becomes the owner. */
    CHANGES_ON_RELEASE("CHANGES_ON_RELEASE");

    private final String configValue;

    CaptureItemOwnershipMode(@Nonnull String configValue) {
        this.configValue = configValue;
    }

    @Nonnull
    public String toConfigValue() {
        return configValue;
    }

    /** Unknown and missing values resolve to {@link #FOLLOWS_ITEM}. */
    @Nonnull
    public static CaptureItemOwnershipMode fromConfigValue(@Nullable String value) {
        CaptureItemOwnershipMode mode = parse(value);
        return mode == null ? FOLLOWS_ITEM : mode;
    }

    /**
     * The mode a settings file selects. {@code value} wins when it names a mode. Otherwise the
     * retired {@code captureClearsOwner} and {@code SpawnSetsOwner} values are mapped: an owner
     * cleared on capture follows the item; a kept owner changes on release when release assigned
     * the owner, and is bound to the owner when it did not. A missing value counts as its old
     * default (true). With nothing present the mode is {@link #FOLLOWS_ITEM}.
     */
    @Nonnull
    public static CaptureItemOwnershipMode resolve(@Nullable String value,
                                                   @Nullable Boolean legacyCaptureClearsOwner,
                                                   @Nullable Boolean legacySpawnSetsOwner) {
        CaptureItemOwnershipMode mode = parse(value);
        if (mode != null) {
            return mode;
        }
        if (legacyCaptureClearsOwner == null && legacySpawnSetsOwner == null) {
            return FOLLOWS_ITEM;
        }
        return fromLegacy(legacyCaptureClearsOwner == null || legacyCaptureClearsOwner,
                legacySpawnSetsOwner == null || legacySpawnSetsOwner);
    }

    /** Maps the two retired settings to a mode. */
    @Nonnull
    public static CaptureItemOwnershipMode fromLegacy(boolean captureClearsOwner, boolean spawnSetsOwner) {
        if (captureClearsOwner) {
            return FOLLOWS_ITEM;
        }
        return spawnSetsOwner ? CHANGES_ON_RELEASE : OWNER_ONLY;
    }

    @Nullable
    private static CaptureItemOwnershipMode parse(@Nullable String value) {
        if (value != null) {
            for (CaptureItemOwnershipMode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return null;
    }
}
