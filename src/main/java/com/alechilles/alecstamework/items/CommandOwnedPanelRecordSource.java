package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.items.CommandPersistenceView.ProfileSnapshot;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Reads owned companions across worlds from the companion index. */
final class CommandOwnedPanelRecordSource {
    /** Candidate profiles for one owner; a null owner asks for the unowned captured profiles. */
    private final Function<UUID, Collection<ProfileSnapshot>> profiles;
    /** Profiles an owner's generic items show read-only, because another authority manages them. */
    private final Function<UUID, java.util.Set<ProfileId>> managedProfiles;
    /** One profile id to its record when that is an unowned capture item, else null. */
    private final Function<UUID, ProfileSnapshot> unownedCapture;

    /**
     * Owned rows from the companion index, plus the unowned captured rows this viewer knows: the
     * index lists records by owner only, so the profiles of carried capture items and of the
     * item's linked records are looked up one by one. Command-family roster members (a roster id,
     * not bonded) are read-only here: their family item's panel owns their actions. Bonded
     * companions are never listed: they belong to their bonded roster's own panel.
     */
    CommandOwnedPanelRecordSource(CompanionQueries companions) {
        this.profiles = owner -> owner == null ? List.of()
                : companions.owned(owner).stream().filter(record -> !record.bonded())
                        .map(CommandPersistenceView::from).toList();
        this.managedProfiles = owner -> owner == null ? java.util.Set.of()
                : companions.owned(owner).stream()
                        .filter(record -> record.rosterId() != null && !record.bonded())
                        .map(record -> new ProfileId(record.profileId()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.unownedCapture = id -> {
            var record = companions.get(id);
            return record != null && record.ownerUuid() == null
                    && record.location().kind() == LocationKind.ITEM
                    ? CommandPersistenceView.from(record) : null;
        };
    }

    /** The unowned captured rows for the profile ids this viewer carries or has linked. */
    private List<ProfileSnapshot> knownUnownedCaptures(List<LinkedNpcRecord> linkedRecords,
                                                       java.util.Set<String> carriedProfiles) {
        var ids = new java.util.LinkedHashSet<String>(carriedProfiles);
        for (var linked : linkedRecords) if (linked.profileId != null) ids.add(linked.profileId);
        var found = new ArrayList<ProfileSnapshot>();
        for (String id : ids) {
            try {
                var profile = unownedCapture.apply(UUID.fromString(id));
                if (profile != null) found.add(profile);
            } catch (IllegalArgumentException notAProfileId) {
                // Item metadata that names no profile shows nothing.
            }
        }
        return found;
    }

    /** One refresh owns this immutable display snapshot; action handlers still read current authority. */
    record Snapshot(List<LinkedNpcRecord> ownedRecords, List<LinkedNpcRecord> capturedRecords,
                    Map<UUID, ProfileId> profilesByRow,
                    Map<UUID, CommandPanelFeaturePresentation> managedFeatures) { }

    Snapshot snapshot(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords,
                      java.util.Set<String> carriedProfiles) {
        var managed = managedProfiles.apply(ownerUuid);
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
        var candidates = new ArrayList<ProfileSnapshot>(profiles.apply(ownerUuid));
        candidates.addAll(knownUnownedCaptures(linkedRecords, carriedProfiles));
        for (var profile : candidates) {
            String id = profile.profileId().toString();
            UUID currentAlias = profile.currentNpcUuid();
            if (profile.ownerUuid() != null && profile.ownerUuid().equals(ownerUuid)) {
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
                if (linked != null) rows.putIfAbsent(linked.npcUuid, profile.profileId());
                // A restore respawns the companion under a new NPC UUID. Item metadata may still name
                // the retired body, so a live profile's row follows its current body; otherwise the
                // live owner index adds that body as a second card.
                if (linked != null && currentAlias != null && !currentAlias.equals(linked.npcUuid)
                        && (profile.lifecycleState() == LifecycleState.ACTIVE
                        || profile.lifecycleState() == LifecycleState.UNLOADED)) {
                    linked = new LinkedNpcRecord(currentAlias, id, null, null, linked.homePosition,
                            linked.cachedDisplayName, linked.cachedNameKey, linked.cachedRoleId,
                            linked.cachedCommandState, linked.active, linked.breedingEnabled, linked.groupId);
                }
                records.add(linked != null ? linked : displayRecord(profile,
                        currentAlias == null ? presentation : currentAlias, false));
            } else if (profile.ownerUuid() == null && profile.lifecycleState() == LifecycleState.CAPTURED) {
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

    private static LinkedNpcRecord displayRecord(ProfileSnapshot profile, UUID alias, boolean active) {
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
        return profileForRow(ownerUuid, rowUuid, List.of());
    }

    java.util.Optional<ProfileId> profileForRow(UUID ownerUuid, UUID rowUuid,
                                                List<LinkedNpcRecord> linkedRecords) {
        if (ownerUuid == null || rowUuid == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(
                snapshot(ownerUuid, linkedRecords, java.util.Set.of()).profilesByRow().get(rowUuid));
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
