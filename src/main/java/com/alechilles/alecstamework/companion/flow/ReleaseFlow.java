package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owner release and cull (spec 8.12). The record becomes a RELEASED tombstone at once and the
 * loaded body is unregistered, so its removal is not seen as a loss. The caller removes the
 * returned body on its world thread; an unloaded body is removed by the fence when it loads.
 * The snapshot delete is queued as soon as the tombstone is in the index. The writer holds that
 * delete until the owner file that held the record is written, and retries it after a failed
 * write, so the snapshot survives until the release is on disk (plan 1 ledger).
 *
 * <p>Safe from any thread: it only changes the in-memory index and never waits on the writer.</p>
 */
public final class ReleaseFlow {
    public enum Result { RELEASED, NOT_FOUND, NOT_OWNER, NOT_RELEASABLE }

    /** {@code body} is the unregistered loaded body, valid when returned, or null when none was loaded. */
    public record Outcome(@Nonnull Result result, @Nullable Ref<EntityStore> body) {
    }

    /** The old panel allowed release of active, unloaded, dead and lost companions. */
    private static final Set<LocationKind> RELEASABLE = EnumSet.of(LocationKind.LIVE, LocationKind.DEAD, LocationKind.LOST);

    private final CompanionIndex index;
    private final LoadedBodies<Ref<EntityStore>> loaded;
    private final Consumer<UUID> deleteSnapshot;

    public ReleaseFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<Ref<EntityStore>> loaded,
                       @Nonnull Consumer<UUID> deleteSnapshot) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
    }

    /** @param actingOwner the player releasing, or null for an admin or system release. */
    @Nonnull
    public Outcome release(@Nonnull UUID profileId, @Nullable UUID actingOwner) {
        Outcome outcome = index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record == null) {
                return new Outcome(Result.NOT_FOUND, null);
            }
            if (actingOwner != null && !actingOwner.equals(record.ownerUuid())) {
                return new Outcome(Result.NOT_OWNER, null);
            }
            if (!RELEASABLE.contains(record.location().kind())) {
                return new Outcome(Result.NOT_RELEASABLE, null);
            }
            if (!index.update(profileId, record.revision(), CompanionTransitions.released(record)).applied()) {
                return new Outcome(Result.NOT_RELEASABLE, null);
            }
            Ref<EntityStore> body = loaded.get(profileId);
            if (body != null) {
                loaded.removeIfSame(profileId, body);
            }
            return new Outcome(Result.RELEASED, body != null && body.isValid() ? body : null);
        });
        if (outcome.result() == Result.RELEASED) {
            deleteSnapshot.accept(profileId);
        }
        return outcome;
    }
}
