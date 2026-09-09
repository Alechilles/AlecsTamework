package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Reads owned companions across worlds from the existing immutable profile projection. */
final class CommandOwnedPanelRecordSource {
    private final Supplier<Map<ProfileId, CompanionProfileProjectionState>> profiles;

    CommandOwnedPanelRecordSource(
            Supplier<Map<ProfileId, CompanionProfileProjectionState>> profiles) {
        this.profiles = profiles;
    }

    List<LinkedNpcRecord> recordsFor(UUID ownerUuid) {
        return recordsFor(ownerUuid, List.of());
    }

    List<LinkedNpcRecord> recordsFor(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords) {
        if (ownerUuid == null) return List.of();
        Map<String, LinkedNpcRecord> linkedByProfile = new HashMap<>();
        Map<UUID, LinkedNpcRecord> linkedByAlias = new HashMap<>();
        for (LinkedNpcRecord record : linkedRecords) {
            linkedByProfile.put(record.profileId == null ? record.npcUuid.toString() : record.profileId, record);
            linkedByAlias.put(record.npcUuid, record);
        }
        ArrayList<LinkedNpcRecord> records = new ArrayList<>();
        for (CompanionProfileProjectionState profile : profiles.get().values()) {
            if (profile.ownerId() == null
                    || !ownerUuid.equals(profile.ownerId().value())
                    || profile.lifecycleState() == LifecycleState.RELEASED) continue;
            LinkedNpcRecord linked = linkedByProfile.get(profile.profileId().toString());
            if (linked == null && profile.currentAlias() != null) {
                linked = linkedByAlias.get(profile.currentAlias().value());
            }
            if (linked != null) {
                records.add(linked);
                continue;
            }
            UUID alias = profile.currentAlias() == null
                    ? CommandRosterPanelRecordSource.presentationUuid(profile.profileId())
                    : profile.currentAlias().value();
            records.add(new LinkedNpcRecord(alias, profile.profileId().toString(),
                    null, null, null,
                    profile.customName() != null ? profile.customName() : profile.displayName(),
                    null, profile.roleId(), null, true, false, null));
        }
        records.sort(Comparator.comparing(record -> record.profileId == null
                ? record.npcUuid.toString() : record.profileId));
        return List.copyOf(records);
    }
}
