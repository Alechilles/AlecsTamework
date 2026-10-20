package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.ProfileChangeType;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Computes the public profile change vocabulary between two profile views. */
public final class CompanionProfileApiMapper {
    private CompanionProfileApiMapper() {
    }

    /** Computes the stable change vocabulary for public profile values. */
    @Nonnull
    public static EnumSet<ProfileChangeType> diff(
            @Nullable NpcProfileView before,
            @Nullable NpcProfileView after
    ) {
        EnumSet<ProfileChangeType> changes =
                EnumSet.noneOf(ProfileChangeType.class);
        if (before == null && after == null) {
            return changes;
        }
        if (before == null) {
            changes.add(ProfileChangeType.CREATED);
        }
        changed(changes, ProfileChangeType.CURRENT_NPC_UUID,
                before == null ? null : before.currentNpcUuid(),
                after == null ? null : after.currentNpcUuid());
        if (!Objects.equals(
                before == null ? null : before.ownerUuid(),
                after == null ? null : after.ownerUuid()
        ) || !Objects.equals(
                before == null ? null : before.ownerName(),
                after == null ? null : after.ownerName()
        )) {
            changes.add(ProfileChangeType.OWNER);
        }
        changed(changes, ProfileChangeType.ROLE,
                before == null ? null : before.roleId(),
                after == null ? null : after.roleId());
        changed(changes, ProfileChangeType.DISPLAY_NAME,
                before == null ? null : before.displayName(),
                after == null ? null : after.displayName());
        changed(changes, ProfileChangeType.CUSTOM_NAME,
                before == null ? null : before.customName(),
                after == null ? null : after.customName());
        changed(changes, ProfileChangeType.TAMED,
                before != null && before.tamed(),
                after != null && after.tamed());
        if (!Objects.equals(
                before == null ? null : before.coopId(),
                after == null ? null : after.coopId()
        ) || !Objects.equals(
                before == null ? null : before.coopSlot(),
                after == null ? null : after.coopSlot()
        )) {
            changes.add(ProfileChangeType.COOP_ASSIGNMENT);
        }
        changed(changes, ProfileChangeType.TOOL_LINKS,
                before == null ? Set.of() : before.toolIds(),
                after == null ? Set.of() : after.toolIds());
        changed(changes, ProfileChangeType.ACTIVE_SNAPSHOTS,
                before == null ? Set.of() : before.activeSnapshotTypes(),
                after == null ? Set.of() : after.activeSnapshotTypes());
        return changes;
    }

    private static void changed(
            EnumSet<ProfileChangeType> changes,
            ProfileChangeType type,
            Object before,
            Object after
    ) {
        if (!Objects.equals(before, after)) {
            changes.add(type);
        }
    }
}
