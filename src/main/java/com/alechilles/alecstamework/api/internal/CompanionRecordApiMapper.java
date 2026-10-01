package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.OwnedTraitSnapshot;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.progression.TraitPresentationViewMapper;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maps companion index records to the public profile contract. Pure: it reads only the
 * immutable record, so it is safe from any thread.
 */
public final class CompanionRecordApiMapper {
    /** Snapshot type of a companion held in a capture item. */
    public static final String SNAPSHOT_CAPTURE = "capture";
    /** Snapshot type of a dead companion. */
    public static final String SNAPSHOT_DEATH = "death";
    /** Snapshot type of a lost companion. */
    public static final String SNAPSHOT_LOST = "lost";

    private CompanionRecordApiMapper() {
    }

    /**
     * Builds the public profile view. A record with an owner is tamed. The coop id is the
     * address of the coop block ({@code world:x:y:z}), because the record holds where the
     * companion is housed and not the coop's asset id.
     */
    @Nonnull
    public static NpcProfileView toProfileView(@Nonnull CompanionRecord record) {
        CompanionLocation location = record.location();
        boolean inCoop = location.kind() == LocationKind.COOP;
        String type = snapshotType(record);
        return new NpcProfileView(
                record.profileId().toString(),
                record.currentNpcUuid(),
                record.ownerUuid(),
                record.ownerName(),
                record.roleId(),
                record.displayName(),
                record.summary().customName(),
                record.countsAsOwned(),
                inCoop ? coopId(location) : null,
                inCoop ? Integer.valueOf(location.slot()) : null,
                new LinkedHashSet<>(record.toolIds()),
                type == null ? Set.of() : Set.of(type),
                record.updatedAtMs()
        );
    }

    /**
     * The active snapshot type of a record, from where the companion is: {@code capture} in an
     * item, {@code death} when dead, {@code lost} when lost, otherwise null.
     */
    @Nullable
    public static String snapshotType(@Nonnull CompanionRecord record) {
        return switch (record.location().kind()) {
            case ITEM -> SNAPSHOT_CAPTURE;
            case DEAD -> SNAPSHOT_DEATH;
            case LOST -> SNAPSHOT_LOST;
            default -> null;
        };
    }

    /**
     * A small JSON description of the record's active snapshot, or null when it has none.
     * It is built from the record only; the full body snapshot is not exposed.
     */
    @Nullable
    public static String snapshotJson(@Nonnull CompanionRecord record) {
        String type = snapshotType(record);
        if (type == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("snapshotType", type);
        json.addProperty("profileId", record.profileId().toString());
        json.addProperty("roleId", record.roleId());
        if (record.ownerUuid() != null) {
            json.addProperty("ownerUuid", record.ownerUuid().toString());
        }
        if (record.displayName() != null) {
            json.addProperty("displayName", record.displayName());
        }
        if (record.summary().customName() != null) {
            json.addProperty("customName", record.summary().customName());
        }
        if (record.location().cause() != null) {
            json.addProperty("cause", record.location().cause());
        }
        if (record.diedAtMs() != 0L) {
            json.addProperty("diedAtMs", record.diedAtMs());
        }
        if (record.reviveAvailableAtMs() != 0L) {
            json.addProperty("reviveAvailableAtMs", record.reviveAvailableAtMs());
        }
        json.addProperty("createdAtMs", record.lastSnapshotAtMs());
        json.addProperty("updatedAtMs", record.updatedAtMs());
        return json.toString();
    }

    /** The position stored on the record: set for a companion in a world or housed in a coop. */
    @Nullable
    public static Vector3View lastKnownPosition(@Nonnull CompanionRecord record) {
        CompanionLocation location = record.location();
        return location.kind() == LocationKind.LIVE || location.kind() == LocationKind.COOP
                ? new Vector3View(location.x(), location.y(), location.z())
                : null;
    }

    /**
     * Builds the saved trait row from the record summary. Trait data is unavailable when the
     * companion has no saved summary yet or its trait config no longer resolves.
     *
     * @param traitConfigs resolves a trait config id; may return null or throw for an unknown id
     */
    @Nonnull
    public static OwnedTraitSnapshot toOwnedTraitSnapshot(
            @Nonnull CompanionRecord record,
            @Nonnull Function<String, TwTraitConfig> traitConfigs
    ) {
        CompanionSummary summary = record.summary();
        String configId = summary.traitsConfigId();
        TwTraitConfig config = null;
        if (summary.observedAtMs() > 0L && configId != null) {
            try {
                config = traitConfigs.apply(configId);
            } catch (RuntimeException | LinkageError failure) {
                config = null;
            }
        }
        boolean available = config != null;
        return new OwnedTraitSnapshot(
                record.profileId().toString(),
                record.roleId(),
                record.displayName(),
                configId,
                available
                        ? TraitPresentationViewMapper.map(configId, 0L, summary.traits(), config).values()
                        : List.of(),
                available,
                Math.max(0L, summary.observedAtMs())
        );
    }

    @Nonnull
    private static String coopId(@Nonnull CompanionLocation location) {
        return location.world() + ":" + (long) location.x() + ":" + (long) location.y() + ":" + (long) location.z();
    }
}
