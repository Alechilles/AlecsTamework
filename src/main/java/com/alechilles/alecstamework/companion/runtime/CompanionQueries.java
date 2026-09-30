package com.alechilles.alecstamework.companion.runtime;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lock-free companion reads for gameplay and UI (spec 6.7, 9). Safe from any thread; returns
 * immutable records. {@link #loadedBody} returns a ref only while it is valid, and callers use
 * it only on that ref's world thread.
 */
public final class CompanionQueries {
    private final CompanionIndex index;
    private final LoadedBodies<Ref<EntityStore>> loaded;

    public CompanionQueries(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<Ref<EntityStore>> loaded) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Nullable
    public CompanionRecord get(@Nonnull UUID profileId) {
        return index.get(profileId);
    }

    @Nullable
    public CompanionRecord byNpcUuid(@Nonnull UUID npcUuid) {
        return index.byNpcUuid(npcUuid);
    }

    /** Every non-released record of {@code owner}. */
    @Nonnull
    public List<CompanionRecord> owned(@Nonnull UUID owner) {
        return index.fileRecords(owner).stream().filter(CompanionRecord::countsAsOwned).toList();
    }

    @Nullable
    public Ref<EntityStore> loadedBody(@Nonnull UUID profileId) {
        Ref<EntityStore> ref = loaded.get(profileId);
        return ref != null && ref.isValid() ? ref : null;
    }
}
