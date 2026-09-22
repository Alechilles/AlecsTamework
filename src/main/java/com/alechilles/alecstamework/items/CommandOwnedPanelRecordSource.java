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

    /** One refresh owns this immutable display snapshot; action handlers still read current authority. */
    record Snapshot(List<LinkedNpcRecord> ownedRecords, List<LinkedNpcRecord> capturedRecords,
                    Map<UUID, ProfileId> profilesByRow,
                    Map<UUID, CommandPanelFeaturePresentation> managedFeatures) { }

    Snapshot snapshot(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords,
                      java.util.Set<String> carriedProfiles) {
        var managed = managedProfiles.get();
        var linkedByProfile = new HashMap<String, LinkedNpcRecord>();
        var linkedByAlias = new HashMap<UUID, LinkedNpcRecord>();
        var firstByProfile = new HashMap<String, IndexedRecord>();
        var firstByAlias = new HashMap<UUID, IndexedRecord>();
        var aliasesByProfile = new HashMap<String, List<UUID>>();
        for (int i = 0; i < linkedRecords.size(); i++) {
            var linked = linkedRecords.get(i);
            linkedByProfile.put(linked.profileId == null ? linked.npcUuid.toString() : linked.profileId, linked);
            linkedByAlias.put(linked.npcUuid, linked);
            firstByAlias.putIfAbsent(linked.npcUuid, new IndexedRecord(i, linked));
            if (linked.profileId != null) {
                firstByProfile.putIfAbsent(linked.profileId, new IndexedRecord(i, linked));
                aliasesByProfile.computeIfAbsent(linked.profileId, ignored -> new ArrayList<>()).add(linked.npcUuid);
            }
        }
        var records = new ArrayList<LinkedNpcRecord>();
        var captures = new ArrayList<LinkedNpcRecord>();
        var rows = new HashMap<UUID, ProfileId>();
        var features = new HashMap<UUID, CommandPanelFeaturePresentation>();
        for (var profile : profiles.get().values()) {
            String id = profile.profileId().toString();
            UUID currentAlias = profile.currentAlias() == null ? null : profile.currentAlias().value();
            if (profile.ownerId() != null && profile.ownerId().value().equals(ownerUuid)) {
                UUID presentation = CommandRosterPanelRecordSource.presentationUuid(profile.profileId());
                if (managed.contains(profile.profileId()) || profile.lifecycleState() == LifecycleState.ROSTER_STORED
                        || profile.lifecycleState() == LifecycleState.PROVISIONED_DORMANT) {
                    var feature = CommandPanelFeaturePresentation.readOnlyManaged();
                    features.put(presentation, feature);
                    if (currentAlias != null) features.put(currentAlias, feature);
                    for (UUID alias : aliasesByProfile.getOrDefault(id, List.of())) features.put(alias, feature);
                }
                if (profile.lifecycleState() == LifecycleState.RELEASED) continue;
                rows.putIfAbsent(presentation, profile.profileId());
                if (currentAlias != null) rows.putIfAbsent(currentAlias, profile.profileId());
                var linked = linkedByProfile.get(id);
                if (linked == null && currentAlias != null) linked = linkedByAlias.get(currentAlias);
                records.add(linked != null ? linked : displayRecord(profile,
                        currentAlias == null ? presentation : currentAlias, false));
            } else if (profile.ownerId() == null && profile.lifecycleState() == LifecycleState.CAPTURED) {
                // Preserve the first matching legacy record when profile and alias matches differ.
                var match = firstByProfile.get(id);
                var aliasMatch = firstByAlias.get(currentAlias);
                if (match == null || aliasMatch != null && aliasMatch.index() < match.index()) match = aliasMatch;
                if (match == null && !carriedProfiles.contains(id)) continue;
                UUID alias = match != null ? match.record().npcUuid : currentAlias == null
                        ? CommandRosterPanelRecordSource.presentationUuid(profile.profileId()) : currentAlias;
                captures.add(displayRecord(profile, alias, match != null && match.record().active));
            }
        }
        records.sort(Comparator.comparing(record -> record.profileId == null
                ? record.npcUuid.toString() : record.profileId));
        return new Snapshot(List.copyOf(records), List.copyOf(captures), Map.copyOf(rows), Map.copyOf(features));
    }

    private record IndexedRecord(int index, LinkedNpcRecord record) { }

    private static LinkedNpcRecord displayRecord(CompanionProfileProjectionState profile, UUID alias, boolean active) {
        return new LinkedNpcRecord(alias, profile.profileId().toString(), null, null, null,
                profile.customName() != null ? profile.customName() : profile.displayName(),
                null, profile.roleId(), null, active, false, null);
    }

    Map<UUID, CommandPanelFeaturePresentation> managedFeatures(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords) {
        return snapshot(ownerUuid, linkedRecords, java.util.Set.of()).managedFeatures();
    }

    Map<UUID, ProfileId> profilesByRow(UUID ownerUuid) {
        return snapshot(ownerUuid, List.of(), java.util.Set.of()).profilesByRow();
    }

    /** Resolves a server-generated row freshly; display snapshots never authorize actions. */
    java.util.Optional<ProfileId> profileForRow(UUID ownerUuid, UUID rowUuid) {
        if (ownerUuid == null || rowUuid == null) return java.util.Optional.empty();
        for (var profile : profiles.get().values()) {
            if (profile.ownerId() == null || !ownerUuid.equals(profile.ownerId().value())
                    || profile.lifecycleState() == LifecycleState.RELEASED) continue;
            if (profile.currentAlias() != null && rowUuid.equals(profile.currentAlias().value())
                    || rowUuid.equals(CommandRosterPanelRecordSource.presentationUuid(profile.profileId()))) {
                return java.util.Optional.of(profile.profileId());
            }
        }
        return java.util.Optional.empty();
    }

    List<LinkedNpcRecord> capturedRecordsFor(List<LinkedNpcRecord> linkedRecords,
                                            java.util.Set<String> carriedProfiles) {
        return snapshot(null, linkedRecords, carriedProfiles).capturedRecords();
    }

    List<LinkedNpcRecord> recordsFor(UUID ownerUuid) {
        return recordsFor(ownerUuid, List.of());
    }

    List<LinkedNpcRecord> recordsFor(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords) {
        return snapshot(ownerUuid, linkedRecords, java.util.Set.of()).ownedRecords();
    }
}
