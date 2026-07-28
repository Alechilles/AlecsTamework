package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rebuilds the ownership-bearing parts of a full-state projection from one canonical owner.
 *
 * <p>Capture and release lifecycle ownership must agree with the snapshot restored into the live
 * entity. Name attribution stored by the NPC-name component is intentionally independent.</p>
 */
final class SpawnerFullStateOwnershipNormalizer {

    @Nonnull
    CoopResidentStateSnapshot normalize(
            @Nonnull CoopResidentStateSnapshot source,
            @Nullable OwnerId ownerId,
            @Nullable String ownerName
    ) {
        Objects.requireNonNull(source, "source");
        TameworkCommandLinksComponent links = source.commandLinks();
        TameworkCommandLinksComponent normalizedLinks = links == null
                ? null
                : new TameworkCommandLinksComponent(
                        ownerId == null ? null : ownerId.value(),
                        links.getToolIds(),
                        links.getHomePosition()
                );
        TameworkOwnerComponent normalizedOwner = ownerId == null
                ? null
                : new TameworkOwnerComponent(
                        ownerId.value(),
                        normalize(ownerName)
                );
        return new CoopResidentStateSnapshot(
                source.npcUuid(),
                source.coopId(),
                source.residentSlot(),
                source.roleId(),
                normalizedLinks,
                normalizedOwner,
                source.tamed(),
                source.npcName(),
                source.happiness(),
                source.needs(),
                source.breeding(),
                source.leveling(),
                source.traits(),
                source.talents(),
                source.lifeStage(),
                source.attachments(),
                source.healthPercent(),
                source.capturedAtMs()
        );
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
