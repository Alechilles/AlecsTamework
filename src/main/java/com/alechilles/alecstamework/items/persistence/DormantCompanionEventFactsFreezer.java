package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Copies the released dormant-event fields out of complete snapshot components. */
final class DormantCompanionEventFactsFreezer {
    @Nonnull
    DormantCompanionEventFacts freeze(@Nonnull CoopResidentStateSnapshot state) {
        if (state == null) {
            throw new IllegalArgumentException(
                    "Dormant full state is required for event facts"
            );
        }
        TameworkCommandLinksComponent links = state.commandLinks();
        TameworkOwnerComponent owner = state.owner();
        return new DormantCompanionEventFacts(
                state.npcUuid(),
                ownerId(owner, links),
                owner == null ? null : owner.getOwnerName(),
                toolIds(links),
                state.roleId(),
                state.npcName() == null ? null : state.npcName().getName(),
                state.tamed() != null && state.tamed().isTamed(),
                home(links)
        );
    }

    @Nullable
    private UUID ownerId(
            @Nullable TameworkOwnerComponent owner,
            @Nullable TameworkCommandLinksComponent links
    ) {
        if (owner != null && owner.getOwnerId() != null) {
            return owner.getOwnerId();
        }
        return links == null ? null : links.getOwnerId();
    }

    private Set<String> toolIds(
            @Nullable TameworkCommandLinksComponent links
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (links == null || links.getToolIds() == null) {
            return result;
        }
        for (String value : links.getToolIds()) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    @Nullable
    private DormantCompanionObservation.PositionObservation home(
            @Nullable TameworkCommandLinksComponent links
    ) {
        return links == null || !links.isHasHome()
                ? null
                : new DormantCompanionObservation.PositionObservation(
                        links.getHomeX(),
                        links.getHomeY(),
                        links.getHomeZ()
                );
    }
}
