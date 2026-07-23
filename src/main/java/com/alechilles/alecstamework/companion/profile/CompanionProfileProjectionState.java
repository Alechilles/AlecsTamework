package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable, self-contained state used by public profile views and change projections.
 *
 * <p>This is derived evidence, never a persistence authority. Keeping it in the outbox event
 * prevents projection consumers from re-reading mutable state after the canonical transaction.</p>
 */
public record CompanionProfileProjectionState(
        @Nonnull ProfileId profileId,
        @Nullable NpcAlias currentAlias,
        @Nullable OwnerId ownerId,
        @Nullable String ownerName,
        @Nullable String roleId,
        @Nullable String displayName,
        @Nullable String customName,
        boolean tamed,
        @Nullable String coopId,
        @Nullable Integer coopSlot,
        @Nonnull Set<UUID> toolIds,
        @Nonnull Set<SnapshotKind> activeSnapshotKinds,
        long lastUpdatedAtMs
) {
    public CompanionProfileProjectionState {
        if (profileId == null || toolIds == null || activeSnapshotKinds == null) {
            throw new IllegalArgumentException("Complete profile projection state is required");
        }
        ownerName = normalize(ownerName);
        roleId = normalize(roleId);
        displayName = normalize(displayName);
        customName = normalize(customName);
        coopId = normalize(coopId);
        toolIds = Set.copyOf(toolIds);
        activeSnapshotKinds = Set.copyOf(activeSnapshotKinds);
    }

    /** Composes one projection state from the focused canonical authorities. */
    @Nonnull
    public static CompanionProfileProjectionState compose(
            @Nonnull CompanionIdentity identity,
            @Nullable CompanionAlias currentAlias,
            @Nonnull CompanionLifecycle lifecycle,
            @Nonnull List<CompanionToolLink> toolLinks,
            @Nonnull List<CompanionSnapshot> currentSnapshots,
            @Nullable CoopSlot currentCoopSlot
    ) {
        if (identity == null || lifecycle == null
                || toolLinks == null || currentSnapshots == null
                || !identity.profileId().equals(lifecycle.profileId())) {
            throw new IllegalArgumentException("Consistent profile authorities are required");
        }
        ProfileMetadata metadata = ProfileMetadata.decode(identity.metadataJson());
        LinkedHashSet<UUID> tools = new LinkedHashSet<>();
        toolLinks.stream()
                .sorted(Comparator.comparing(link -> link.toolId().toString()))
                .forEach(link -> {
                    requireProfile(identity.profileId(), link.profileId(), "tool_link");
                    tools.add(link.toolId());
                });
        LinkedHashSet<SnapshotKind> snapshots = new LinkedHashSet<>();
        currentSnapshots.stream()
                .sorted(Comparator.comparing(CompanionSnapshot::kind))
                .forEach(snapshot -> {
                    requireProfile(identity.profileId(), snapshot.profileId(), "snapshot");
                    if (!snapshot.current()) {
                        throw new IllegalArgumentException(
                                "Profile projection snapshots must be current"
                        );
                    }
                    snapshots.add(snapshot.kind());
                });
        if (currentAlias != null) {
            requireProfile(identity.profileId(), currentAlias.profileId(), "alias");
            if (currentAlias.state() != CompanionAlias.State.CURRENT) {
                throw new IllegalArgumentException(
                        "Profile projection alias must be current"
                );
            }
        }
        return new CompanionProfileProjectionState(
                identity.profileId(),
                currentAlias == null ? null : currentAlias.alias(),
                lifecycle.ownerId(),
                metadata.ownerName(),
                identity.roleId(),
                identity.displayName(),
                metadata.customName(),
                metadata.tamed(),
                currentCoopSlot == null
                        ? null
                        : currentCoopSlot.key().coopId(),
                currentCoopSlot == null
                        ? null
                        : currentCoopSlot.key().residentSlot(),
                tools,
                snapshots,
                latestUpdate(identity, currentAlias, lifecycle, toolLinks, currentSnapshots)
        );
    }

    private static long latestUpdate(
            CompanionIdentity identity,
            CompanionAlias alias,
            CompanionLifecycle lifecycle,
            List<CompanionToolLink> links,
            List<CompanionSnapshot> snapshots
    ) {
        long latest = Math.max(identity.updatedAtMs(), lifecycle.stateChangedAtMs());
        if (alias != null) {
            latest = Math.max(latest, alias.mappedAtMs());
        }
        for (CompanionToolLink link : links) {
            latest = Math.max(latest, link.updatedAtMs());
        }
        for (CompanionSnapshot snapshot : snapshots) {
            latest = Math.max(latest, snapshot.createdAtMs());
        }
        return latest;
    }

    private static void requireProfile(ProfileId expected, ProfileId actual, String kind) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Profile projection " + kind + " mismatch");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ProfileMetadata(
            @Nullable String ownerName,
            @Nullable String customName,
            boolean tamed,
            @Nullable String coopId,
            @Nullable Integer coopSlot
    ) {
        private static ProfileMetadata decode(String raw) {
            JsonObject json = parseObject(raw);
            return new ProfileMetadata(
                    text(json, "owner_name"),
                    text(json, "custom_name"),
                    bool(json, "tamed"),
                    text(json, "coop_id"),
                    integer(json, "coop_slot")
            );
        }

        private static JsonObject parseObject(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return JsonParser.parseString(raw).getAsJsonObject();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static String text(JsonObject json, String name) {
            JsonElement value = value(json, name);
            try {
                return value == null ? null : normalize(value.getAsString());
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static boolean bool(JsonObject json, String name) {
            JsonElement value = value(json, name);
            try {
                return value != null && value.getAsBoolean();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        private static Integer integer(JsonObject json, String name) {
            JsonElement value = value(json, name);
            try {
                return value == null ? null : value.getAsInt();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static JsonElement value(JsonObject json, String name) {
            return json == null || !json.has(name) || json.get(name).isJsonNull()
                    ? null
                    : json.get(name);
        }
    }
}
