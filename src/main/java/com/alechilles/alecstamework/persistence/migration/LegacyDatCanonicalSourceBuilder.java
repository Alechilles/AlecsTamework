package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.migration.PublicImportPlanningSupport.deterministicId;

/** Converts decoded DAT rows into the immutable public-source model used by canonical planning. */
final class LegacyDatCanonicalSourceBuilder {
    LegacyPublicData build(
            LegacyDatRows rows,
            LegacySourceFingerprint fingerprint,
            long importedAtMs
    ) throws PublicImportException {
        TreeMap<String, ProfileAccumulator> profiles = new TreeMap<>();
        ArrayList<LegacyPublicData.Snapshot> snapshots = new ArrayList<>();
        LinkedHashSet<ToolLinkKey> toolLinks = new LinkedHashSet<>();
        LinkedHashMap<String, Flags> flags = new LinkedHashMap<>();
        long sourceSnapshotId = 1L;

        for (LegacyDatRows.Snapshot snapshot : rows.snapshots()) {
            ProfileAccumulator profile = profile(
                    profiles, snapshot.npcUuid(), fingerprint, importedAtMs);
            profile.merge(snapshot);
            flags.computeIfAbsent(snapshot.npcUuid(), ignored -> new Flags())
                    .mark(snapshot.kind());
            snapshots.add(new LegacyPublicData.Snapshot(
                    sourceSnapshotId++,
                    profile.profileId,
                    snapshot.kind(),
                    1,
                    snapshot.payloadJson(),
                    1,
                    snapshot.eventAtMs()
            ));
            addToolLinks(toolLinks, profile.profileId, snapshot, importedAtMs);
        }

        ArrayList<LegacyPublicData.CoopSlot> coopSlots = new ArrayList<>();
        for (LegacyDatRows.CoopSlot slot : rows.coopSlots().stream()
                .sorted(Comparator.comparing(LegacyDatRows.CoopSlot::sourceKey))
                .toList()) {
            String profileId = null;
            String profileNpcUuid = slot.profileNpcUuid();
            if (profileNpcUuid != null) {
                ProfileAccumulator profile = profile(
                        profiles, profileNpcUuid, fingerprint, importedAtMs);
                profile.merge(slot);
                profileId = profile.profileId;
                Flags profileFlags = flags.computeIfAbsent(
                        profileNpcUuid, ignored -> new Flags());
                if (slot.housedNpcUuid() != null) {
                    profileFlags.coopKeys.add(slot.sourceKey());
                }
                addToolLinks(toolLinks, profile.profileId, "coop",
                        slot.toolIds(), importedAtMs);
            }
            coopSlots.add(new LegacyPublicData.CoopSlot(
                    slot.worldName(),
                    slot.coopId(),
                    slot.x(),
                    slot.y(),
                    slot.z(),
                    slot.residentSlot(),
                    profileId,
                    slot.housedNpcUuid(),
                    slot.lastReleasedNpcUuid(),
                    slot.housedAtMs(),
                    slot.releasedAtMs(),
                    importedAtMs,
                    null
            ));
        }

        ArrayList<LegacyPublicData.Profile> profileRows = new ArrayList<>();
        ArrayList<LegacyPublicData.Alias> aliases = new ArrayList<>();
        ArrayList<LegacyPublicData.ProfileState> states = new ArrayList<>();
        for (Map.Entry<String, ProfileAccumulator> entry : profiles.entrySet()) {
            ProfileAccumulator profile = entry.getValue();
            profileRows.add(profile.toProfile());
            aliases.add(new LegacyPublicData.Alias(
                    entry.getKey(), profile.profileId, 1, importedAtMs));
            Flags state = flags.getOrDefault(entry.getKey(), new Flags());
            states.add(new LegacyPublicData.ProfileState(
                    profile.profileId,
                    state.capture ? 1 : 0,
                    state.death ? 1 : 0,
                    state.lost ? 1 : 0,
                    state.coopKeys.isEmpty() ? 0 : 1,
                    state.coopKeys.isEmpty() ? null : state.coopKeys.iterator().next(),
                    importedAtMs
            ));
        }

        return new LegacyPublicData(
                profileRows,
                aliases,
                toolLinks.stream()
                        .map(ToolLinkKey::toSource)
                        .sorted(Comparator.comparing(LegacyPublicData.ToolLink::profileId)
                                .thenComparing(LegacyPublicData.ToolLink::linkType)
                                .thenComparing(LegacyPublicData.ToolLink::toolUuid))
                        .toList(),
                snapshots,
                coopSlots,
                states,
                List.of()
        );
    }

    private ProfileAccumulator profile(
            Map<String, ProfileAccumulator> profiles,
            String npcUuid,
            LegacySourceFingerprint fingerprint,
            long importedAtMs
    ) {
        return profiles.computeIfAbsent(npcUuid, ignored -> new ProfileAccumulator(
                deterministicId(fingerprint.snapshotSha256(), "legacy-dat-profile:" + npcUuid),
                npcUuid,
                importedAtMs
        ));
    }

    private void addToolLinks(
            LinkedHashSet<ToolLinkKey> target,
            String profileId,
            LegacyDatRows.Snapshot snapshot,
            long importedAtMs
    ) {
        addToolLinks(target, profileId, snapshot.kind(), snapshot.toolIds(), importedAtMs);
        if ("capture".equals(snapshot.kind()) || "death".equals(snapshot.kind())) {
            target.removeIf(link -> link.profileId().equals(profileId)
                    && "profile".equals(link.linkType()));
            addToolLinks(target, profileId, "profile", snapshot.toolIds(), importedAtMs);
        }
    }

    private void addToolLinks(
            LinkedHashSet<ToolLinkKey> target,
            String profileId,
            String linkType,
            List<String> toolIds,
            long importedAtMs
    ) {
        for (String toolId : toolIds) {
            target.add(new ToolLinkKey(
                    profileId, toolId, linkType, importedAtMs, importedAtMs));
        }
    }

    private static final class ProfileAccumulator {
        private final String profileId;
        private final String currentNpcUuid;
        private final long importedAtMs;
        private final JsonObject metadata = new JsonObject();
        @Nullable
        private String ownerUuid;
        @Nullable
        private String displayName;
        @Nullable
        private String roleId;

        private ProfileAccumulator(
                String profileId,
                String currentNpcUuid,
                long importedAtMs
        ) {
            this.profileId = profileId;
            this.currentNpcUuid = currentNpcUuid;
            this.importedAtMs = importedAtMs;
        }

        private void merge(LegacyDatRows.Snapshot snapshot) throws PublicImportException {
            mergeOwner(snapshot.ownerUuid());
            roleId = latest(roleId, snapshot.roleId());
            displayName = latest(displayName, snapshot.displayName());
            put("owner_name", snapshot.ownerName());
            put("custom_name", snapshot.customName());
            if (snapshot.tamed() != null) {
                metadata.addProperty("tamed", snapshot.tamed());
            }
        }

        private void merge(LegacyDatRows.CoopSlot slot) throws PublicImportException {
            mergeOwner(slot.ownerUuid());
            roleId = latest(roleId, slot.roleId());
            displayName = latest(displayName, slot.displayName());
            metadata.addProperty("coop_id", slot.coopId());
            metadata.addProperty("coop_slot", slot.residentSlot());
        }

        private void mergeOwner(@Nullable String candidate) throws PublicImportException {
            if (candidate == null) {
                return;
            }
            if (ownerUuid != null && !ownerUuid.equals(candidate)) {
                throw new PublicImportException(
                        "CONFLICTING_LEGACY_DAT_OWNER",
                        "Conflicting owners for legacy NPC " + currentNpcUuid
                );
            }
            ownerUuid = candidate;
        }

        private void put(String field, @Nullable String value) {
            if (value != null && !value.isBlank()) {
                metadata.addProperty(field, value);
            }
        }

        private LegacyPublicData.Profile toProfile() {
            String stateJson = metadata.isEmpty() ? null : metadata.toString();
            return new LegacyPublicData.Profile(
                    profileId,
                    currentNpcUuid,
                    ownerUuid,
                    displayName,
                    roleId,
                    stateJson,
                    stateJson == null ? null : Integer.toHexString(stateJson.hashCode()),
                    null,
                    importedAtMs,
                    importedAtMs,
                    importedAtMs
            );
        }

        @Nullable
        private String latest(@Nullable String current, @Nullable String candidate) {
            return candidate == null || candidate.isBlank() ? current : candidate;
        }
    }

    private static final class Flags {
        private boolean capture;
        private boolean death;
        private boolean lost;
        private final LinkedHashSet<String> coopKeys = new LinkedHashSet<>();

        private void mark(String kind) {
            switch (kind) {
                case "capture" -> capture = true;
                case "death" -> death = true;
                case "lost" -> lost = true;
                default -> throw new IllegalArgumentException("Unsupported DAT snapshot kind");
            }
        }
    }

    private record ToolLinkKey(
            String profileId,
            String toolUuid,
            String linkType,
            long createdAtMs,
            long updatedAtMs
    ) {
        private LegacyPublicData.ToolLink toSource() {
            return new LegacyPublicData.ToolLink(
                    profileId, toolUuid, linkType, createdAtMs, updatedAtMs);
        }
    }
}
