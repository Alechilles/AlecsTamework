package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
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

/**
 * Reads the companions a viewer's generic panel lists, across worlds, from the companion index:
 * the viewer's own, and those other owners share with the viewer.
 */
final class CommandOwnedPanelRecordSource {
    /**
     * Most shared rows one viewer's snapshot lists. Own rows are bounded by the population cap;
     * shared rows are not, and every listed row costs world-thread work on panel builds and
     * multi-row actions.
     */
    static final int MAX_SHARED_ROWS = 200;

    /** Candidate profiles for one owner; a null owner asks for the unowned captured profiles. */
    private final Function<UUID, Collection<ProfileSnapshot>> profiles;
    /** Other owners' companions the viewer may see in panels, each with the viewer's level on it. */
    private final Function<UUID, Map<ProfileId, SharedProfile>> sharedProfiles;
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
     *
     * <p>Shared rows come from the same index: one in-memory pass over each sharing owner's
     * records per snapshot, with no file read. They are an authorization view only. Caps, counts
     * and everything written to a record keep using the record's owner.</p>
     */
    CommandOwnedPanelRecordSource(CompanionQueries companions) {
        this.profiles = owner -> owner == null ? List.of()
                : companions.owned(owner).stream().filter(record -> !record.bonded())
                        .map(CommandPersistenceView::from).toList();
        this.sharedProfiles = viewer -> {
            var owners = viewer == null ? java.util.Set.<UUID>of() : SharedAccessService.get().ownersSharingWith(viewer);
            if (owners.isEmpty()) return Map.of();
            List<Map.Entry<CompanionRecord, AccessLevel>> visible = new ArrayList<>();
            for (UUID owner : owners) {
                for (CompanionRecord record : companions.owned(owner)) {
                    // The access check already gives nothing for bonded and private companions.
                    // Another owner's command-family member or stored body is left out: only its
                    // family item acts on it, so a row here could offer nothing.
                    if (record.rosterId() != null || record.location().kind() == LocationKind.STORED) continue;
                    AccessLevel level = CompanionAccess.level(record, viewer);
                    if (AccessAction.VIEW_IN_PANELS.allowedAt(level)) visible.add(Map.entry(record, level));
                }
            }
            if (visible.size() > MAX_SHARED_ROWS) {
                // Owners come from a set, so order by profile id to keep the same rows each refresh.
                visible.sort(Comparator.comparing(entry -> entry.getKey().profileId()));
                visible = visible.subList(0, MAX_SHARED_ROWS);
            }
            var shared = new java.util.LinkedHashMap<ProfileId, SharedProfile>();
            for (var entry : visible) {
                var profile = CommandPersistenceView.from(entry.getKey());
                shared.put(profile.profileId(),
                        new SharedProfile(profile, new SharedRow(entry.getKey().ownerName(), entry.getValue())));
            }
            return shared;
        };
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

    /** The owner's name and the viewer's level on a companion another owner shares with the viewer. */
    record SharedRow(String ownerName, AccessLevel level) { }

    private record SharedProfile(ProfileSnapshot profile, SharedRow row) { }

    /**
     * One refresh owns this immutable display snapshot; action handlers still read current authority.
     * {@code ownedRecords} lists the viewer's own companions and the shared ones; {@code sharedRows}
     * names the shared ones by every row id they answer to.
     */
    record Snapshot(List<LinkedNpcRecord> ownedRecords, List<LinkedNpcRecord> capturedRecords,
                    Map<UUID, ProfileId> profilesByRow,
                    Map<UUID, CommandPanelFeaturePresentation> managedFeatures,
                    Map<UUID, SharedRow> sharedRows) { }

    /** {@code ownerUuid} is the viewer: the owner of the listed rows, except for the shared ones. */
    Snapshot snapshot(UUID ownerUuid, List<LinkedNpcRecord> linkedRecords,
                      java.util.Set<String> carriedProfiles) {
        var managed = managedProfiles.apply(ownerUuid);
        var shared = sharedProfiles.apply(ownerUuid);
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
        var sharedRows = new HashMap<UUID, SharedRow>();
        var candidates = new ArrayList<ProfileSnapshot>(profiles.apply(ownerUuid));
        for (var sharedProfile : shared.values()) candidates.add(sharedProfile.profile());
        candidates.addAll(knownUnownedCaptures(linkedRecords, carriedProfiles));
        for (var profile : candidates) {
            String id = profile.profileId().toString();
            UUID currentAlias = profile.currentNpcUuid();
            SharedProfile sharedProfile = shared.get(profile.profileId());
            if (profile.ownerUuid() != null && (profile.ownerUuid().equals(ownerUuid) || sharedProfile != null)) {
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
                LinkedNpcRecord row = linked != null ? linked : displayRecord(profile,
                        currentAlias == null ? presentation : currentAlias, false);
                records.add(row);
                if (sharedProfile != null) {
                    // Every id the row can be shown or clicked under, as profilesByRow maps them.
                    sharedRows.put(presentation, sharedProfile.row());
                    sharedRows.put(row.npcUuid, sharedProfile.row());
                    if (currentAlias != null) sharedRows.put(currentAlias, sharedProfile.row());
                    for (UUID alias : aliasesByProfile.getOrDefault(id, List.of())) {
                        sharedRows.put(alias, sharedProfile.row());
                    }
                }
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
        return new Snapshot(List.copyOf(records), List.copyOf(captures), Map.copyOf(rows), Map.copyOf(features),
                Map.copyOf(sharedRows));
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
