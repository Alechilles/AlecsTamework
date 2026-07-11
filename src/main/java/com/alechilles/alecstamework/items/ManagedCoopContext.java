package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Exact Tamework authority context for one configured physical coop block.
 *
 * <p>The vanilla block component is discovery evidence only. Occupancy identity is always the
 * normalized world plus exact block coordinates, with the configured coop ID retained as an
 * independent match guard.</p>
 */
public final class ManagedCoopContext {
    private final ManagedCoopAuthorityKey authorityKey;
    private final String coopId;
    private final int blockRotationIndex;
    private final TwCoopConfig config;
    @Nullable
    private final ItemContainer container;
    private final String coopKey;

    ManagedCoopContext(@Nonnull ManagedCoopAuthorityKey authorityKey,
                       @Nonnull String coopId,
                       int blockRotationIndex,
                       @Nonnull TwCoopConfig config,
                       @Nullable ItemContainer container) {
        this.authorityKey = Objects.requireNonNull(authorityKey, "authorityKey");
        this.coopId = normalizeRequired(coopId, "coopId");
        this.config = Objects.requireNonNull(config, "config");
        if (!config.isManagedAuthorityEnabled()) {
            throw new IllegalArgumentException("config is not eligible for managed-coop authority");
        }
        if (!this.coopId.equals(normalizeRequired(config.getCoopId(), "config.coopId"))) {
            throw new IllegalArgumentException("context coopId does not match config coopId");
        }
        this.blockRotationIndex = blockRotationIndex;
        this.container = container;
        this.coopKey = authorityKey.authorityId() + "|coop=" + this.coopId;
    }

    @Nonnull
    public ManagedCoopAuthorityKey authorityKey() {
        return authorityKey;
    }

    @Nonnull
    public String worldName() {
        return authorityKey.worldName();
    }

    @Nonnull
    public String coopId() {
        return coopId;
    }

    @Nonnull
    public Vector3i block() {
        return new Vector3i(authorityKey.x(), authorityKey.y(), authorityKey.z());
    }

    public int blockRotationIndex() {
        return blockRotationIndex;
    }

    @Nonnull
    public TwCoopConfig config() {
        return config;
    }

    @Nullable
    public ItemContainer container() {
        return container;
    }

    @Nonnull
    public String coopKey() {
        return coopKey;
    }

    @Nonnull
    public CommandLinkedNpcCoopService.CoopSlotContext slotContext(int residentSlot) {
        return CommandLinkedNpcCoopService.CoopSlotContext.of(
                authorityKey.worldName(),
                coopId,
                authorityKey.x(),
                authorityKey.y(),
                authorityKey.z(),
                residentSlot
        );
    }

    public boolean matchesExact(@Nullable String worldName,
                                @Nullable String coopId,
                                int x,
                                int y,
                                int z) {
        return authorityKey.worldName().equals(normalizeNullable(worldName))
                && this.coopId.equals(normalizeNullable(coopId))
                && authorityKey.x() == x
                && authorityKey.y() == y
                && authorityKey.z() == z;
    }

    @Nonnull
    private static String normalizeRequired(@Nullable String value, @Nonnull String field) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
