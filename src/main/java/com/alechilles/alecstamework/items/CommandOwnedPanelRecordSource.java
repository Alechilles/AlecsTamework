package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
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
    private final Supplier<java.util.Set<ProfileId>> managedProfiles;

    CommandOwnedPanelRecordSource(
            Supplier<Map<ProfileId, CompanionProfileProjectionState>> profiles) {
        this(profiles, java.util.Set::of);
    }

    CommandOwnedPanelRecordSource(
            Supplier<Map<ProfileId, CompanionProfileProjectionState>> profiles,
            Supplier<java.util.Set<ProfileId>> managedProfiles) {
        this.profiles = profiles;
        this.managedProfiles = managedProfiles;
    }

    Map<UUID, CommandPanelFeaturePresentation> managedFeatures(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords) {
        var managed = managedProfiles.get();
        Map<UUID, CommandPanelFeaturePresentation> result = new HashMap<>();
        for (var profile : profiles.get().values()) {
            if (profile.ownerId() == null || !profile.ownerId().value().equals(ownerUuid)) continue;
            if (!managed.contains(profile.profileId())
                    && profile.lifecycleState() != LifecycleState.ROSTER_STORED
                    && profile.lifecycleState() != LifecycleState.PROVISIONED_DORMANT) continue;
            var feature = CommandPanelFeaturePresentation.readOnlyManaged();
            result.put(CommandRosterPanelRecordSource.presentationUuid(profile.profileId()), feature);
            if (profile.currentAlias() != null) result.put(profile.currentAlias().value(), feature);
            for (var linked : linkedRecords) {
                if (profile.profileId().toString().equals(linked.profileId)) result.put(linked.npcUuid, feature);
            }
        }
        return Map.copyOf(result);
    }

    /** Resolves a server-generated Owned row without treating its UUID as ownership authority. */
    java.util.Optional<ProfileId> profileForRow(UUID ownerUuid, UUID rowUuid) {
        if (ownerUuid == null || rowUuid == null) return java.util.Optional.empty();
        return profiles.get().values().stream()
                .filter(profile -> profile.ownerId() != null
                        && ownerUuid.equals(profile.ownerId().value())
                        && profile.lifecycleState() != LifecycleState.RELEASED)
                .filter(profile -> rowUuid.equals(CommandRosterPanelRecordSource.presentationUuid(profile.profileId()))
                        || profile.currentAlias() != null && rowUuid.equals(profile.currentAlias().value()))
                .map(CompanionProfileProjectionState::profileId).findFirst();
    }

    /** Display-only captures: current inventory or a pre-existing tool record, never ownership. */
    List<LinkedNpcRecord> capturedRecordsFor(List<LinkedNpcRecord> linkedRecords,
                                             java.util.Set<String> carriedProfiles) {
        ArrayList<LinkedNpcRecord> result = new ArrayList<>();
        for (var profile : profiles.get().values()) {
            if (profile.ownerId() != null || profile.lifecycleState() != LifecycleState.CAPTURED) continue;
            var linked = linkedRecords.stream().filter(record ->
                    profile.profileId().toString().equals(record.profileId)
                    || profile.currentAlias() != null && profile.currentAlias().value().equals(record.npcUuid))
                    .findFirst().orElse(null);
            if (linked == null && !carriedProfiles.contains(profile.profileId().toString())) continue;
            UUID alias = linked != null ? linked.npcUuid : profile.currentAlias() == null
                    ? CommandRosterPanelRecordSource.presentationUuid(profile.profileId()) : profile.currentAlias().value();
            result.add(new LinkedNpcRecord(alias, profile.profileId().toString(),
                    null, null, null, profile.customName() != null ? profile.customName() : profile.displayName(),
                    null, profile.roleId(), null, false, false, null));
        }
        return List.copyOf(result);
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
                    null, profile.roleId(), null, false, false, null));
        }
        records.sort(Comparator.comparing(record -> record.profileId == null
                ? record.npcUuid.toString() : record.profileId));
        return List.copyOf(records);
    }
}
