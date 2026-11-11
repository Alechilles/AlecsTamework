package com.alechilles.alecstamework.api.access;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared access to companions: an owner lets other players care for, handle or manage their
 * companions without giving up ownership. Advertised by
 * {@code TameworkApiCapability.SHARED_ACCESS}.
 *
 * <p>A companion always has exactly one owner. {@code PolicyApi.isOwner} keeps its strict
 * meaning; use {@link #level} or {@link #allows} to ask what another player may do.</p>
 *
 * <p>Every method is safe from any thread and returns at once. Reads are in-memory lookups;
 * changes are saved in the background.</p>
 */
public interface SharedAccessApi {
    /** Whether shared access exists in this runtime. False for the unavailable facade. */
    boolean available();

    /** Whether the server has sharing turned on. Grants are kept while it is off. */
    boolean enabled();

    /** The highest level the server lets owners grant. */
    @Nonnull
    AccessLevel maxLevel();

    /**
     * The level {@code actorUuid} holds on a companion of {@code ownerUuid}.
     *
     * @param profileId the companion's profile id; when given, a private or bonded companion
     *                  answers {@link AccessLevel#NONE} for everyone but the owner. Null asks about
     *                  the owner's companions in general.
     * @return {@link AccessLevel#OWNER} when the two ids are equal, {@link AccessLevel#NONE} when
     *         either id is null
     */
    @Nonnull
    AccessLevel level(@Nullable UUID ownerUuid, @Nullable UUID actorUuid, @Nullable UUID profileId);

    /**
     * The level {@code actorUuid} holds on the companion with this profile id, read from the
     * companion index. {@link AccessLevel#NONE} for an unknown or unowned profile.
     */
    @Nonnull
    AccessLevel levelForProfile(@Nonnull UUID profileId, @Nullable UUID actorUuid);

    /** True when {@code actorUuid} may do {@code action} with a companion of {@code ownerUuid}. */
    default boolean allows(@Nullable UUID ownerUuid, @Nullable UUID actorUuid, @Nullable UUID profileId,
                           @Nonnull AccessAction action) {
        return action.allowedAt(level(ownerUuid, actorUuid, profileId));
    }

    /** Everything {@code ownerUuid} shares with: named players first, then groups. */
    @Nonnull
    List<AccessGrantView> grants(@Nonnull UUID ownerUuid);

    /** True when the owner marked this companion private, or it is never shared (bonded). */
    boolean isPrivate(@Nonnull UUID ownerUuid, @Nonnull UUID profileId);

    /** Owners whose grants give {@code actorUuid} some access right now. */
    @Nonnull
    Set<UUID> ownersSharingWith(@Nonnull UUID actorUuid);

    /** Shares {@code ownerUuid}'s companions with a named player, or changes that player's level. */
    @Nonnull
    AccessChangeResult grantPlayer(@Nonnull UUID ownerUuid, @Nonnull UUID playerUuid,
                                   @Nullable String playerName, @Nonnull AccessLevel level);

    /** Removes a named player's grant. */
    @Nonnull
    AccessChangeResult revokePlayer(@Nonnull UUID ownerUuid, @Nonnull UUID playerUuid);

    /** Turns a group share on at {@code level}, or off when {@code level} is null. */
    @Nonnull
    AccessChangeResult setGroup(@Nonnull UUID ownerUuid, @Nonnull String groupId, @Nullable AccessLevel level);

    /** Marks one companion private, so no grant applies to it, or shares it again. */
    @Nonnull
    AccessChangeResult setPrivate(@Nonnull UUID ownerUuid, @Nonnull UUID profileId, boolean isPrivate);

    /**
     * Registers a group that owners can choose to share with, and returns an idempotent handle
     * that removes it. Registering grants nothing by itself. Owners' saved choices for the group
     * survive while it is unregistered and apply again when it returns.
     *
     * @throws IllegalStateException if a provider with the same id is registered, or this facade
     *                               is unavailable
     */
    @Nonnull
    AutoCloseable registerGroup(@Nonnull AccessGroupProvider provider);

    /** Returns a shared facade that grants nothing, for older and degraded API compositions. */
    @Nonnull
    static SharedAccessApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation: only an owner has access to their own companions. */
    final class UnavailableHolder {
        private static final SharedAccessApi INSTANCE = new SharedAccessApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public AccessLevel maxLevel() {
                return AccessLevel.NONE;
            }

            @Override
            public AccessLevel level(UUID ownerUuid, UUID actorUuid, UUID profileId) {
                return ownerUuid != null && ownerUuid.equals(actorUuid) ? AccessLevel.OWNER : AccessLevel.NONE;
            }

            @Override
            public AccessLevel levelForProfile(UUID profileId, UUID actorUuid) {
                return AccessLevel.NONE;
            }

            @Override
            public List<AccessGrantView> grants(UUID ownerUuid) {
                return List.of();
            }

            @Override
            public boolean isPrivate(UUID ownerUuid, UUID profileId) {
                return false;
            }

            @Override
            public Set<UUID> ownersSharingWith(UUID actorUuid) {
                return Set.of();
            }

            @Override
            public AccessChangeResult grantPlayer(UUID ownerUuid, UUID playerUuid, String playerName,
                                                  AccessLevel level) {
                return AccessChangeResult.UNAVAILABLE;
            }

            @Override
            public AccessChangeResult revokePlayer(UUID ownerUuid, UUID playerUuid) {
                return AccessChangeResult.UNAVAILABLE;
            }

            @Override
            public AccessChangeResult setGroup(UUID ownerUuid, String groupId, AccessLevel level) {
                return AccessChangeResult.UNAVAILABLE;
            }

            @Override
            public AccessChangeResult setPrivate(UUID ownerUuid, UUID profileId, boolean isPrivate) {
                return AccessChangeResult.UNAVAILABLE;
            }

            @Override
            public AutoCloseable registerGroup(AccessGroupProvider provider) {
                Objects.requireNonNull(provider, "provider");
                throw new IllegalStateException("shared access is not available");
            }
        };

        private UnavailableHolder() {
        }
    }
}
