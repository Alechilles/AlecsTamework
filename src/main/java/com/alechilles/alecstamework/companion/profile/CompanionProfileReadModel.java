package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable public-read composition of the focused companion authorities.
 *
 * <p>No field is a second lifecycle authority; this model is rebuilt from one consistent SQLite
 * read connection.</p>
 */
public record CompanionProfileReadModel(
        @Nonnull CompanionIdentity identity,
        @Nullable CompanionAlias currentAlias,
        @Nonnull CompanionLifecycle lifecycle,
        @Nonnull List<CompanionToolLink> toolLinks,
        @Nonnull List<CompanionSnapshot> currentSnapshots,
        @Nullable CoopSlot currentCoopSlot
) {
    public CompanionProfileReadModel {
        if (identity == null || lifecycle == null) {
            throw new IllegalArgumentException("Profile identity and lifecycle are required");
        }
        toolLinks = List.copyOf(toolLinks);
        currentSnapshots = List.copyOf(currentSnapshots);
        if (!lifecycle.profileId().equals(identity.profileId())) {
            throw new IllegalArgumentException("Profile lifecycle identity mismatch");
        }
        if (currentAlias != null && !currentAlias.profileId().equals(identity.profileId())) {
            throw new IllegalArgumentException("Current alias identity mismatch");
        }
        if (toolLinks.stream().anyMatch(link ->
                !link.profileId().equals(identity.profileId()))) {
            throw new IllegalArgumentException("Tool link identity mismatch");
        }
        if (currentSnapshots.stream().anyMatch(snapshot ->
                !snapshot.profileId().equals(identity.profileId()) || !snapshot.current())) {
            throw new IllegalArgumentException("Current snapshot identity mismatch");
        }
        boolean cooped = lifecycle.state()
                == com.alechilles.alecstamework.companion.lifecycle.LifecycleState.COOP;
        if (cooped != (currentCoopSlot != null)
                || (currentCoopSlot != null && !lifecycle.location().key()
                .equals(currentCoopSlot.key().toString()))) {
            throw new IllegalArgumentException("Profile coop detail/lifecycle mismatch");
        }
    }
}
