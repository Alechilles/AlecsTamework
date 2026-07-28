package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.ProfileChangeType;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps replacement profile projection evidence to the released public API contract. */
public final class CompanionProfileApiMapper {
    private CompanionProfileApiMapper() {
    }

    @Nonnull
    public static NpcProfileView map(@Nonnull CompanionProfileProjectionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Profile projection state is required");
        }
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        state.toolIds().stream().map(Object::toString).sorted().forEach(tools::add);
        LinkedHashSet<String> snapshots = new LinkedHashSet<>();
        state.activeSnapshotKinds().stream()
                .map(kind -> kind.value())
                .sorted()
                .forEach(snapshots::add);
        return new NpcProfileView(
                state.profileId().toString(),
                state.currentAlias() == null ? null : state.currentAlias().value(),
                state.ownerId() == null ? null : state.ownerId().value(),
                state.ownerName(),
                state.roleId(),
                state.displayName(),
                state.customName(),
                state.tamed(),
                state.coopId(),
                state.coopSlot(),
                tools,
                snapshots,
                state.lastUpdatedAtMs()
        );
    }

    @Nonnull
    public static EnumSet<ProfileChangeType> diff(
            @Nullable CompanionProfileProjectionState before,
            @Nullable CompanionProfileProjectionState after
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
                value(before, Field.CURRENT_ALIAS), value(after, Field.CURRENT_ALIAS));
        if (!Objects.equals(value(before, Field.OWNER_ID), value(after, Field.OWNER_ID))
                || !Objects.equals(
                value(before, Field.OWNER_NAME), value(after, Field.OWNER_NAME))) {
            changes.add(ProfileChangeType.OWNER);
        }
        changed(changes, ProfileChangeType.ROLE,
                value(before, Field.ROLE), value(after, Field.ROLE));
        changed(changes, ProfileChangeType.DISPLAY_NAME,
                value(before, Field.DISPLAY_NAME), value(after, Field.DISPLAY_NAME));
        changed(changes, ProfileChangeType.CUSTOM_NAME,
                value(before, Field.CUSTOM_NAME), value(after, Field.CUSTOM_NAME));
        changed(changes, ProfileChangeType.TAMED,
                before != null && before.tamed(), after != null && after.tamed());
        if (!Objects.equals(value(before, Field.COOP_ID), value(after, Field.COOP_ID))
                || !Objects.equals(
                value(before, Field.COOP_SLOT), value(after, Field.COOP_SLOT))) {
            changes.add(ProfileChangeType.COOP_ASSIGNMENT);
        }
        changed(changes, ProfileChangeType.TOOL_LINKS,
                before == null ? Set.of() : before.toolIds(),
                after == null ? Set.of() : after.toolIds());
        changed(changes, ProfileChangeType.ACTIVE_SNAPSHOTS,
                before == null ? Set.of() : before.activeSnapshotKinds(),
                after == null ? Set.of() : after.activeSnapshotKinds());
        return changes;
    }

    /** Computes the same stable change vocabulary for public profile values. */
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

    private static Object value(CompanionProfileProjectionState state, Field field) {
        if (state == null) {
            return null;
        }
        return switch (field) {
            case CURRENT_ALIAS -> state.currentAlias();
            case OWNER_ID -> state.ownerId();
            case OWNER_NAME -> state.ownerName();
            case ROLE -> state.roleId();
            case DISPLAY_NAME -> state.displayName();
            case CUSTOM_NAME -> state.customName();
            case COOP_ID -> state.coopId();
            case COOP_SLOT -> state.coopSlot();
        };
    }

    private enum Field {
        CURRENT_ALIAS,
        OWNER_ID,
        OWNER_NAME,
        ROLE,
        DISPLAY_NAME,
        CUSTOM_NAME,
        COOP_ID,
        COOP_SLOT
    }
}
